#!/usr/bin/env python3
"""PR 변경분을 Gemini에 보내 리뷰를 받고, PR 댓글 하나로 남긴다 (새 커밋이 오면 같은 댓글을 고친다).

- 참고용이다. API 오류·키 없음이어도 실패로 끝내지 않는다 (PR을 막지 않는다).
- 정답 파일·바이너리·대용량 파일은 보내지 않는다. 정답 파일이 바뀌면 LLM 없이 직접 경고를 붙인다.
- 보낸 양에 상한이 있다. 넘친 파일은 숨기지 않고 "리뷰하지 못한 파일"로 댓글에 적는다.

로컬 확인: DRY_RUN=1 BASE_SHA=origin/main HEAD_SHA=HEAD python3 scripts/ai-review/review.py
표준 라이브러리만 쓴다.
"""
import fnmatch
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

MARKER = "<!-- ai-review -->"
MODEL = os.environ.get("GEMINI_MODEL") or "gemini-3.6-flash"
MAX_DIFF_CHARS = int(os.environ.get("MAX_DIFF_CHARS", "120000"))
THINKING_LEVEL = os.environ.get("GEMINI_THINKING_LEVEL", "low")  # 빈 값이면 모델 기본값
TIMEOUT_S = int(os.environ.get("GEMINI_TIMEOUT_S", "300"))
API_URL = "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"

# 리뷰에 보내지 않는 파일. 정답 파일은 기준 구현이 만든 숫자라 사람이 읽을 대상이 아니다
EXCLUDE = [
    "src/test/resources/golden/*",
    "*.pdf", "*.pptx", "*.docx", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.svg",
    "*.jar", "gradlew", "gradlew.bat", "gradle/wrapper/*",
    "docs/example/*", "*.html",
]
GOLDEN_PREFIX = "src/test/resources/golden/"

SYSTEM_PROMPT = """너는 이 저장소의 시니어 백엔드 리뷰어다. 한국어로 답한다.

저장소: 사회초년생 금융 시뮬레이터 백엔드 (Spring Boot 4, Java 21, Gradle).
꼭 지켜야 하는 규칙:
- 서버는 사용자 입력을 저장하지 않는다 (NFR-06). 로그·캐시·파일·DB에 입력값이 남으면 문제다.
- 계산 엔진은 기준 구현(Python/JS)과 정답 파일(golden.json, analysis_golden.json)로 대조한다. 계산 결과가 바뀌는 변경은 정답 파일 재생성 근거가 있어야 한다.
- 난수는 sfc32이며 호출 순서가 바뀌면 확률·분위수가 전부 달라진다.
- 금액 단위(원 / 만 원)와 연이율(소수 / 퍼센트) 변환 실수를 특히 본다.
- 경로 수(2,000)와 기간은 보고서 기준이라 바꾸면 안 된다.
- 응답 시간 목표: /simulate 1초, /compare·/reverse 3초 (NFR-03). 엔진은 경로를 parallel()로 계산한다.

리뷰 방식:
- 버그, 경계값, 동시성, 보안, 위 규칙 위반, 테스트가 빠진 동작만 지적한다. 스타일·취향·이름 짓기는 지적하지 않는다.
- 확신이 없으면 "확인 필요"라고 쓰고 무엇을 확인하면 되는지 적는다. 추측을 사실처럼 쓰지 않는다.
- diff에 없는 코드는 모른다고 가정한다. 보이지 않는 코드에 대해 단정하지 않는다.
- 최대 8개, 심각한 것부터.

출력 형식 (마크다운):
### 요약
한두 문장.

### 지적 사항
각 항목:
**[높음|중간|낮음] `파일경로:줄`** 문제 한 줄
- 이유:
- 제안:

지적할 것이 없으면 "### 지적 사항" 아래에 "특별한 문제를 찾지 못했습니다."라고만 쓴다.
"""


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
    return "".join(included), skipped, truncated, golden_changed


def _post(api_key, body, timeout):
    req = urllib.request.Request(
        API_URL.format(model=MODEL),
        data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json", "x-goog-api-key": api_key},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=timeout) as res:
        return json.load(res)


def call_gemini(api_key, user_text):
    gen_config = {"maxOutputTokens": 8192}
    # 리뷰에는 깊은 추론이 필요 없다. 기본값으로 두면 오래 생각하다 시간 초과가 났다 (PR #6)
    if THINKING_LEVEL:
        gen_config["thinkingConfig"] = {"thinkingLevel": THINKING_LEVEL}
    body = {
        "systemInstruction": {"parts": [{"text": SYSTEM_PROMPT}]},
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


def upsert_comment(repo, pr, body):
    comments = json.loads(sh("gh", "api", f"repos/{repo}/issues/{pr}/comments", "--paginate", "--slurp"))
    flat = [c for page in comments for c in page]
    mine = [c for c in flat if MARKER in (c.get("body") or "") and c.get("user", {}).get("type") == "Bot"]
    payload = "/tmp/ai-review-comment.json"
    with open(payload, "w") as f:
        json.dump({"body": body}, f)
    if mine:
        sh("gh", "api", "-X", "PATCH", f"repos/{repo}/issues/comments/{mine[-1]['id']}", "--input", payload)
        print(f"기존 리뷰 댓글을 고쳤습니다 (id {mine[-1]['id']})")
    else:
        sh("gh", "api", "-X", "POST", f"repos/{repo}/issues/{pr}/comments", "--input", payload)
        print("리뷰 댓글을 새로 달았습니다")


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

    diff, skipped, truncated, golden_changed = collect_diff(base, head)
    print(f"보낼 diff {len(diff):,}자 / 제외 {len(skipped)}개 / 상한 초과 {len(truncated)}개 / 정답 파일 변경 {len(golden_changed)}개")

    title = os.environ.get("PR_TITLE", "")
    pr_body = (os.environ.get("PR_BODY") or "")[:3000]
    user_text = f"PR 제목: {title}\n\nPR 설명:\n{pr_body or '(없음)'}\n\n변경분(diff):\n```diff\n{diff}\n```"

    if dry_run:
        print("--- DRY_RUN: API를 부르지 않습니다 ---")
        print("제외:", skipped)
        print("상한 초과:", truncated)
        print("정답 파일:", golden_changed)
        return

    if not diff.strip():
        notice("리뷰할 코드 변경이 없어 건너뜁니다")
        return

    api_key = os.environ.get("GEMINI_API_KEY")
    if not api_key:
        notice("GEMINI_API_KEY 시크릿이 없어 AI 리뷰를 건너뜁니다")
        return

    try:
        review = call_gemini(api_key, user_text)
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

    short = head[:7] if len(head) >= 7 else head
    body = "\n\n".join([
        MARKER,
        f"## AI 코드 리뷰 (`{MODEL}`, 커밋 `{short}`)",
        *extra,
        review.strip(),
        "---",
        "<sub>참고용 자동 리뷰입니다. 틀릴 수 있으니 확인 후 반영하고, 머지 판단은 사람이 합니다. "
        "변경분은 Google Gemini API(무료 등급)로 전송되며, 무료 등급 입력은 Google 제품 개선에 쓰일 수 있습니다. "
        "설정: `scripts/ai-review/review.py`</sub>",
    ])

    repo, pr = os.environ["GITHUB_REPOSITORY"], os.environ["PR_NUMBER"]
    try:
        upsert_comment(repo, pr, body)
    except subprocess.CalledProcessError as e:
        notice(f"댓글 작성 실패: {e.stderr[:300]}")


if __name__ == "__main__":
    main()
