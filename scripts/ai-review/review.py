#!/usr/bin/env python3
"""PR 변경분을 Gemini에 보내 리뷰를 받고, 새 PR 댓글로 남긴다 (이전 리뷰 댓글은 지우지 않고 접어 둔다).

- 참고용이다. API 오류·키 없음이어도 실패로 끝내지 않는다 (PR을 막지 않는다).
- 정답 파일·바이너리·대용량 파일은 보내지 않는다. 정답 파일이 바뀌면 LLM 없이 직접 경고를 붙인다.
- 보낸 양에 상한이 있다. 넘친 파일은 숨기지 않고 "리뷰하지 못한 파일"로 댓글에 적는다.

로컬 확인: DRY_RUN=1 BASE_SHA=origin/main HEAD_SHA=HEAD python3 scripts/ai-review/review.py
표준 라이브러리만 쓴다.
"""
import fnmatch
import json
import os
import re
import subprocess
from collections import Counter
import sys
import time
import urllib.error
import urllib.request

MARKER = "<!-- ai-review -->"
MODEL = os.environ.get("GEMINI_MODEL") or "gemini-3.6-flash"
MAX_DIFF_CHARS = int(os.environ.get("MAX_DIFF_CHARS", "120000"))
THINKING_LEVEL = os.environ.get("GEMINI_THINKING_LEVEL", "low")  # 빈 값이면 모델 기본값
TIMEOUT_S = int(os.environ.get("GEMINI_TIMEOUT_S", "300"))
# diff만 보내면 바뀌지 않은 클래스의 동작을 추측해 오탐이 났다 (review/1111: NoiseSource를 상태 있는 객체로 가정).
# 그래서 diff가 참조하는 저장소 클래스의 정의와 관련 테스트를 맥락으로 함께 보낸다.
MAX_CONTEXT_CHARS = int(os.environ.get("MAX_CONTEXT_CHARS", "60000"))
MAX_CONTEXT_FILE_CHARS = int(os.environ.get("MAX_CONTEXT_FILE_CHARS", "20000"))
# 정의가 칸을 다 차지해 테스트가 밀려나지 않도록 칸과 분량을 나눈다
MAX_CONTEXT_DEFS = int(os.environ.get("MAX_CONTEXT_DEFS", "8"))
MAX_CONTEXT_TESTS = int(os.environ.get("MAX_CONTEXT_TESTS", "4"))
CONTEXT_TEST_SHARE = 0.4  # 맥락 분량 중 테스트 몫
TYPE_RE = re.compile(r"\b([A-Z][A-Za-z0-9_]*[a-z][A-Za-z0-9_]*)\b")
API_URL = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"

# 리뷰에 보내지 않는 파일. 정답 파일은 기준 구현이 만든 숫자라 사람이 읽을 대상이 아니다
EXCLUDE = [
    "src/test/resources/golden/*",
    "*.pdf", "*.pptx", "*.docx", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.svg",
    "*.jar", "gradlew", "gradlew.bat", "gradle/wrapper/*",
    "docs/example/*", "*.html",
]
GOLDEN_PREFIX = "src/test/resources/golden/"

# 저장소 설계 설명. 리뷰 기준(규칙·흐름·오탐 주의점)은 스크립트가 아니라 이 문서에서 관리한다.
# review/1111 재리뷰에서 "@Valid 누락"(검증은 서비스의 checkRequired가 함), "NoiseSource 상태"(호출마다 새로 생성)
# 오탐이 남아, 파일 조각 대신 시스템 전체 흐름을 먼저 알려 주기로 했다.
SYSTEM_CONTEXT_PATH = "docs/architecture/review-context.md"
FALLBACK_CONTEXT = """사회초년생 금융 시뮬레이터 백엔드 (Spring Boot 4, Java 21). 입력을 저장하지 않는다(NFR-06).
계산은 기준 구현과 정답 파일(golden.json, analysis_golden.json)로 대조한다. 난수는 sfc32. 금액은 API 원 / 엔진 만 원."""

SYSTEM_PROMPT = """너는 이 저장소의 시니어 백엔드 리뷰어다. 한국어로 답한다.

맨 아래 "시스템 설명"이 이 저장소의 실제 설계다. 리뷰 전에 먼저 읽는다.
- 설명과 다르게 동작하는 변경(예: 새 계산 API가 checkRequired를 부르지 않음, 같은 경로를 다른 기간으로 비교)을 찾는다.
- 설명에 "일부러 이렇게 한다"고 적힌 설계는 지적하지 않는다.

리뷰 방식:
- 버그, 경계값, 동시성, 보안, 시스템 설명의 규칙 위반, 테스트가 빠진 동작만 지적한다. 스타일·취향·이름 짓기는 지적하지 않는다.
- 확신이 없으면 "확인 필요"라고 쓰고 무엇을 확인하면 되는지 적는다. 추측을 사실처럼 쓰지 않는다.
- "참고 파일"은 이번 PR에서 바뀌지 않은 클래스 정의와 관련 테스트다. 바뀐 코드가 다른 클래스의 동작에 기대는 지적을 하기 전에, 참고 파일에서 그 클래스의 실제 구현과 테스트를 먼저 확인한다.
- 참고 파일로 확인되면 그것을 근거로 판단한다. 기존 테스트가 이미 그 동작을 고정하고 있으면 지적하지 않는다.
- 참고 파일에도 없는 코드는 모른다고 가정한다. "~라면 문제다" 같은 가정에 기댄 지적은 "확인 필요"로 낮추고 무엇을 확인할지 적는다.
- 참고 파일 자체의 문제는 이번 PR 범위 밖이라 지적하지 않는다.
- 최대 8개, 심각한 것부터.

출력 형식 (마크다운):
### 요약
한두 문장.

### 지적 사항
각 항목:
**[높음|중간|낮음] `파일경로:줄`** 문제 한 줄
- 이유:
- 근거: 확인한 코드나 테스트 (`파일:줄` 또는 테스트 이름)와 **그 줄의 코드를 원문 그대로 한 줄 인용**. 인용한 코드가 지적 내용을 실제로 뒷받침하는지 다시 확인하고, 아니면 지적을 빼거나 "확인 필요"로 낮춘다. 근거를 못 찾았으면 "확인 필요: 무엇을 보면 되는지"
- 제안:

지적할 것이 없으면 "### 지적 사항" 아래에 "특별한 문제를 찾지 못했습니다."라고만 쓴다.
"""


def load_system_prompt():
    """리뷰 규칙 + 저장소 시스템 설명. 설명 문서가 없으면 짧은 기본 설명을 쓴다."""
    try:
        with open(SYSTEM_CONTEXT_PATH, encoding="utf-8") as f:
            context, source = f.read(), SYSTEM_CONTEXT_PATH
    except OSError:
        context, source = FALLBACK_CONTEXT, "기본 설명 (문서 없음)"
    return f"{SYSTEM_PROMPT}\n\n---\n시스템 설명 ({source}):\n\n{context}", source


def sh(*args):
    return subprocess.run(args, check=True, capture_output=True, text=True).stdout


def excluded(path):
    return any(fnmatch.fnmatch(path, pat) for pat in EXCLUDE)


def collect_diff(base, head):
    """파일별 diff를 모아 상한 안에서 자른다. (보낸 diff, 제외 파일, 잘린 파일, 정답 파일 변경 여부)"""
    names = [n for n in sh("git", "diff", "--name-only", f"{base}...{head}").splitlines() if n]
    included, skipped, truncated = [], [], []
    golden_changed = [n for n in names if n.startswith(GOLDEN_PREFIX)]
    used = 0
    for name in names:
        if excluded(name):
            skipped.append(name)
            continue
        part = sh("git", "diff", "--unified=5", f"{base}...{head}", "--", name)
        if not part.strip():
            continue
        if used + len(part) > MAX_DIFF_CHARS:
            truncated.append(name)
            continue
        included.append(part)
        used += len(part)
    return "".join(included), skipped, truncated, golden_changed, names


def collect_context(diff, changed, head):
    """diff가 참조하는 저장소 클래스의 정의와 관련 테스트를 모은다.

    반환: (참고 파일 텍스트, [(경로, 이유, 잘림 여부)], 상한 때문에 못 넣은 [(경로, 이유)])
    """
    java = [f for f in sh("git", "ls-tree", "-r", "--name-only", head).splitlines()
            if f.endswith(".java") and f.startswith(("src/main/java/", "src/test/java/"))]
    by_name = {}
    for f in java:
        by_name.setdefault(os.path.basename(f)[:-5], []).append(f)
    changed = set(changed)
    changed_types = {os.path.basename(f)[:-5] for f in changed if f.endswith(".java")}

    # .java 변경분 본문에서만, 저장소에 실제로 있는 클래스 이름을 센다 (문서에 적힌 이름은 세지 않는다).
    # JDK 클래스는 저장소에 파일이 없어 자연히 빠진다
    counts = Counter()
    in_java = False
    for line in diff.splitlines():
        if line.startswith("diff --git"):
            in_java = line.endswith(".java")
            continue
        if not in_java or line.startswith(("index ", "+++", "---", "@@")):
            continue
        for name in TYPE_RE.findall(line):
            if name in by_name and name not in changed_types:
                counts[name] += 1

    defs = []  # 많이 참조된 클래스 정의부터
    for name, n in counts.most_common():
        for f in by_name[name]:
            if f.startswith("src/main/") and f not in changed:
                defs.append((f, f"diff에서 `{name}` {n}회 참조"))

    def tests_mentioning(name):
        try:
            out = sh("git", "grep", "-l", "-w", name, head, "--", "src/test/java")
        except subprocess.CalledProcessError:  # 결과 없음
            return []
        return [line.split(":", 1)[1] for line in out.splitlines() if ":" in line]

    tests = []  # 바뀐 클래스의 테스트 → 많이 참조된 클래스의 테스트
    for name in sorted(t for t in changed_types if any(f.startswith("src/main/") for f in by_name.get(t, []))):
        for f in tests_mentioning(name):
            tests.append((f, f"바뀐 `{name}`의 테스트"))
    for name, _ in counts.most_common(5):
        for f in tests_mentioning(name):
            tests.append((f, f"참조 `{name}`의 테스트"))

    seen, picked, dropped, parts = set(), [], [], []

    def take(cands, max_files, budget):
        n, used = 0, 0
        for path, reason in cands:
            if path in seen or path in changed:
                continue
            seen.add(path)
            if n >= max_files:
                dropped.append((path, reason))
                continue
            src = sh("git", "show", f"{head}:{path}")
            cut = len(src) > MAX_CONTEXT_FILE_CHARS
            if cut:
                src = src[:MAX_CONTEXT_FILE_CHARS] + f"\n// ... (이하 {len(src) - MAX_CONTEXT_FILE_CHARS:,}자 생략)\n"
            if used + len(src) > budget:
                dropped.append((path, reason))
                continue
            parts.append(f"#### {path} ({reason})\n```java\n{src}\n```\n")
            picked.append((path, reason, cut))
            n, used = n + 1, used + len(src)
        return used

    test_budget = int(MAX_CONTEXT_CHARS * CONTEXT_TEST_SHARE)
    used_defs = take(defs, MAX_CONTEXT_DEFS, MAX_CONTEXT_CHARS - test_budget)
    # 정의가 몫을 다 안 썼으면 남은 분량은 테스트가 쓴다
    take(tests, MAX_CONTEXT_TESTS, MAX_CONTEXT_CHARS - used_defs)
    return "".join(parts), picked, dropped


def _post(api_key, body, timeout):
    req = urllib.request.Request(
        API_URL.format(model=MODEL),
        data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json", "x-goog-api-key": api_key},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=timeout) as res:
        return json.load(res)


def call_gemini(api_key, user_text, system_prompt):
    gen_config = {"maxOutputTokens": 8192}
    # 리뷰에는 깊은 추론이 필요 없다. 기본값으로 두면 오래 생각하다 시간 초과가 났다 (PR #6)
    if THINKING_LEVEL:
        gen_config["thinkingConfig"] = {"thinkingLevel": THINKING_LEVEL}
    body = {
        "systemInstruction": {"parts": [{"text": system_prompt}]},
        "contents": [{"role": "user", "parts": [{"text": user_text}]}],
        "generationConfig": gen_config,
    }
    data, last_err = None, None
    for attempt in range(3):
        started = time.time()
        try:
            data = _post(api_key, body, timeout=TIMEOUT_S)
            print(f"Gemini 응답 {time.time() - started:.0f}초 (시도 {attempt + 1})")
            break
        except urllib.error.HTTPError as e:
            detail = e.read().decode(errors="replace")[:500]
            last_err = f"HTTP {e.code}: {detail}"
            # 모델이 thinkingConfig를 거부하면 빼고 다시 보낸다
            if e.code == 400 and "thinking" in detail.lower() and "thinkingConfig" in gen_config:
                print(f"thinkingConfig 거부됨, 빼고 재시도: {detail[:200]}")
                gen_config.pop("thinkingConfig")
                continue
            if e.code in (429, 500, 502, 503, 504) and attempt < 2:
                time.sleep(10 * (attempt + 1))
                continue
            raise RuntimeError(last_err)
        except (urllib.error.URLError, TimeoutError, OSError) as e:
            # 읽기 시간 초과(socket.timeout)는 URLError가 아니라 TimeoutError/OSError로 온다
            last_err = f"{type(e).__name__}: {e} ({time.time() - started:.0f}초)"
            print(f"재시도 {attempt + 1}: {last_err}")
            if attempt < 2:
                time.sleep(5)
                continue
            raise RuntimeError(last_err)
    if data is None:
        raise RuntimeError(last_err or "응답 없음")

    cands = data.get("candidates") or []
    if not cands:
        raise RuntimeError(f"응답에 후보가 없습니다: {json.dumps(data.get('promptFeedback', data))[:500]}")
    parts = cands[0].get("content", {}).get("parts", [])
    text = "".join(p.get("text", "") for p in parts if not p.get("thought"))
    finish = cands[0].get("finishReason", "")
    if not text.strip():
        raise RuntimeError(f"빈 응답 (finishReason={finish})")
    if finish == "MAX_TOKENS":
        text += "\n\n> 응답이 길이 제한에서 잘렸습니다."
    return text


OUTDATED_MARKER = "<!-- ai-review-outdated -->"
HEADER_RE = re.compile(r"^## AI 코드 리뷰 .*$", re.M)


def fold_outdated(old_body):
    """이전 리뷰 댓글을 한 줄 제목 + 접힌 원문으로 바꾼다. 리뷰 이력은 지우지 않고 남긴다 (전후 비교용)."""
    m = HEADER_RE.search(old_body)
    title = m.group(0)[3:] if m else "AI 코드 리뷰"
    inner = old_body.replace(MARKER, "").strip()
    return (f"{OUTDATED_MARKER}\n**이전 {title}** — 새 커밋 기준 리뷰가 아래에 새 댓글로 달려 있습니다.\n\n"
            f"<details><summary>이전 리뷰 펼치기</summary>\n\n{inner}\n\n</details>")


def _gh_json(method, path, body):
    payload = "/tmp/ai-review-comment.json"
    with open(payload, "w") as f:
        json.dump({"body": body}, f)
    return sh("gh", "api", "-X", method, path, "--input", payload)


def post_comment(repo, pr, body):
    """새 리뷰를 새 댓글로 달고, 이전 리뷰 댓글은 접어 둔다."""
    comments = json.loads(sh("gh", "api", f"repos/{repo}/issues/{pr}/comments", "--paginate", "--slurp"))
    flat = [c for page in comments for c in page]
    previous = [c for c in flat if MARKER in (c.get("body") or "") and c.get("user", {}).get("type") == "Bot"]

    # 새 댓글을 먼저 단다. 실패하면 이전 리뷰는 그대로 남는다
    _gh_json("POST", f"repos/{repo}/issues/{pr}/comments", body)
    print("리뷰 댓글을 새로 달았습니다")

    for c in previous:
        try:
            _gh_json("PATCH", f"repos/{repo}/issues/comments/{c['id']}", fold_outdated(c["body"]))
            print(f"이전 리뷰 댓글을 접었습니다 (id {c['id']})")
        except subprocess.CalledProcessError as e:  # 접기 실패는 새 리뷰에 영향 없음
            print(f"이전 리뷰 접기 실패 (id {c['id']}): {e.stderr[:200]}")


def notice(msg):
    print(f"::notice::{msg}")
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a") as f:
            f.write(f"{msg}\n")


def main():
    base = os.environ.get("BASE_SHA", "origin/main")
    head = os.environ.get("HEAD_SHA", "HEAD")
    dry_run = os.environ.get("DRY_RUN") == "1"

    diff, skipped, truncated, golden_changed, changed = collect_diff(base, head)
    print(f"보낼 diff {len(diff):,}자 / 제외 {len(skipped)}개 / 상한 초과 {len(truncated)}개 / 정답 파일 변경 {len(golden_changed)}개")
    context, ctx_files, ctx_dropped = collect_context(diff, changed, head)
    print(f"참고 파일 {len(ctx_files)}개 {len(context):,}자 / 상한 때문에 뺀 참고 파일 {len(ctx_dropped)}개")
    system_prompt, ctx_source = load_system_prompt()
    print(f"시스템 설명: {ctx_source} (프롬프트 {len(system_prompt):,}자)")

    title = os.environ.get("PR_TITLE", "")
    pr_body = (os.environ.get("PR_BODY") or "")[:3000]
    user_text = (
        f"PR 제목: {title}\n\nPR 설명:\n{pr_body or '(없음)'}\n\n"
        f"변경분(diff):\n```diff\n{diff}\n```\n\n"
        f"참고 파일 (이번 PR에서 바뀌지 않음. 판단 근거로만 쓰고 이 파일 자체는 리뷰하지 않는다):\n\n{context or '(없음)'}"
    )

    if dry_run:
        print("--- DRY_RUN: API를 부르지 않습니다 ---")
        print("제외:", skipped)
        print("상한 초과:", truncated)
        print("정답 파일:", golden_changed)
        for path, reason, cut in ctx_files:
            print(f"참고: {path} — {reason}{' (잘림)' if cut else ''}")
        for path, reason in ctx_dropped:
            print(f"참고 못 넣음: {path} — {reason}")
        return

    if not diff.strip():
        notice("리뷰할 코드 변경이 없어 건너뜁니다")
        return

    api_key = os.environ.get("GEMINI_API_KEY")
    if not api_key:
        notice("GEMINI_API_KEY 시크릿이 없어 AI 리뷰를 건너뜁니다")
        return

    try:
        review = call_gemini(api_key, user_text, system_prompt)
    except Exception as e:  # 참고용 리뷰라 실패해도 PR을 막지 않는다
        notice(f"AI 리뷰 실패 (PR에는 영향 없음): {e}")
        return

    extra = []
    if golden_changed:
        extra.append(
            "> **정답 파일이 바뀌었습니다:** " + ", ".join(f"`{n}`" for n in golden_changed)
            + "\n> 기준 구현(`finance-simulator-reference`)에서 다시 만든 파일인지, 바뀐 이유가 PR 설명에 있는지 사람이 확인해 주세요. (이 경고는 LLM이 아니라 스크립트가 붙입니다)"
        )
    if truncated:
        extra.append("> **분량 제한으로 리뷰하지 못한 파일:** " + ", ".join(f"`{n}`" for n in truncated))
    if skipped:
        extra.append("<details><summary>리뷰 대상에서 뺀 파일 " + str(len(skipped)) + "개</summary>\n\n"
                     + "\n".join(f"- `{n}`" for n in skipped) + "\n</details>")
    if ctx_files or ctx_dropped:
        lines = [f"- `{p}` — {r}{' (앞부분만)' if c else ''}" for p, r, c in ctx_files]
        lines += [f"- ~~`{p}`~~ — {r} (분량 상한으로 못 넣음)" for p, r in ctx_dropped]
        dropped_note = f", 분량 상한으로 못 넣은 파일 {len(ctx_dropped)}개" if ctx_dropped else ""
        extra.append(f"<details><summary>맥락으로 함께 보낸 참고 파일 {len(ctx_files)}개{dropped_note}</summary>\n\n"
                     + "\n".join(lines) + "\n</details>")

    short = head[:7] if len(head) >= 7 else head
    body = "\n\n".join([
        MARKER,
        f"## AI 코드 리뷰 (`{MODEL}`, 커밋 `{short}`)",
        *extra,
        review.strip(),
        "---",
        "<sub>참고용 자동 리뷰입니다. 틀릴 수 있으니 확인 후 반영하고, 머지 판단은 사람이 합니다. "
        "변경분은 Google Gemini API(무료 등급)로 전송되며, 무료 등급 입력은 Google 제품 개선에 쓰일 수 있습니다. "
        f"시스템 설명: `{ctx_source}` · 설정: `scripts/ai-review/review.py`</sub>",
    ])

    repo, pr = os.environ["GITHUB_REPOSITORY"], os.environ["PR_NUMBER"]
    try:
        post_comment(repo, pr, body)
    except subprocess.CalledProcessError as e:
        notice(f"댓글 작성 실패: {e.stderr[:300]}")


if __name__ == "__main__":
    main()
