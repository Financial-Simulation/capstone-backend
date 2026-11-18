package com.example.capstoneserver.benefit;

import com.example.capstoneserver.benefit.BenefitRules.Policy;
import com.example.capstoneserver.benefit.dto.BenefitsResponse;
import com.example.capstoneserver.benefit.dto.BenefitsResponse.Effect;
import com.example.capstoneserver.benefit.dto.BenefitsResponse.Item;
import com.example.capstoneserver.benefit.dto.BenefitsResponse.Status;
import com.example.capstoneserver.benefit.dto.Eligibility;
import com.example.capstoneserver.global.response.Warning;
import com.example.capstoneserver.input.dto.SimulationInput;
import com.example.capstoneserver.input.dto.SimulationInput.Event;
import com.example.capstoneserver.input.dto.SimulationInput.EventType;
import com.example.capstoneserver.input.dto.SimulationInput.PresetKey;
import com.example.capstoneserver.plan.dto.Adjustments;
import com.example.capstoneserver.simulation.ParamsRepository;
import com.example.capstoneserver.simulation.SimulationService;
import com.example.capstoneserver.simulation.dto.SimulateResponse;
import com.example.capstoneserver.simulation.engine.WageTax;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 결과 화면 '받을 수 있는 혜택'. 정책마다 받아요 · 확인해 보세요 · 실직하면 · 못 받아요를 고르고,
 * 들어오는 돈이 있는 정책(청년월세 · 소득세 감면 · K-패스)은 그 돈을 더해 목표 달성 가능성을 다시 계산한다.
 * 기준 금액 · 문장 · 출처는 benefits.json, 판정 순서는 여기. 판정은 2026년 기준이다 (2027년 안은 notice에만).
 *
 * 어림인 것 (화면 문장과 assumption에 밝힌다):
 * - 총급여를 안 넣었으면 월 실수령 × 12 ÷ 0.88 (엔진과 같은 규칙)
 * - 청년월세 근로소득은 세전 월급에서 30%를 빼고 본다 (지침 소득평가액)
 * - 들어오는 돈은 엔진의 '지출 변화' 이벤트(마이너스)로 넣는다. 물가 반영 · 실직 중 소비 감소도 같이 적용된다
 */
@Service
@RequiredArgsConstructor
public class BenefitService {

    static final List<String> ASK_KEYS = List.of("livingApart", "sme", "hireMonth", "taxReliefNow", "militaryMonths",
            "parentsIncomeLow", "rentDeposit", "monthlyTransit");

    private final SimulationService simulationService;
    private final BenefitRules rules;
    private final ParamsRepository paramsRepository;

    public BenefitsResponse benefits(SimulationInput input, Adjustments adjustments, Eligibility eligibility, Long seed) {
        SimulationInput used = simulationService.resolve(input, adjustments);
        SimulateResponse sim = simulationService.simulate(used, null, seed);
        Ctx c = new Ctx(used, sim, eligibility == null ? Eligibility.EMPTY : eligibility);

        List<Draft> drafts = new ArrayList<>();
        youthFuture(c, drafts);
        drafts.add(youthRent(c));
        drafts.add(smeTaxRelief(c));
        drafts.add(rentCredit(c));
        drafts.add(housingDeduction(c));
        drafts.add(pensionCredit(c));
        drafts.add(kpass(c));
        drafts.add(naeil(c));
        drafts.add(beotimmok(c));
        drafts.add(employmentSupport(c));

        BigDecimal base = sim.goal().probability();
        List<Item> items = new ArrayList<>();
        for (Draft d : drafts) {
            Effect effect = null;
            if (d.flow != null && d.status != Status.NO && d.status != Status.LATER) {
                BigDecimal p = simulationService.probability(used, d.flow.events(d.policy.name()), seed);
                effect = new Effect(p, p.subtract(base), d.flow.assumption);
            }
            if (d.brief == null) throw new IllegalStateException(d.key + " 짧은 한 줄(brief)이 없어요");
            items.add(new Item(d.key, d.policy.name(), d.policy.category(), d.status, d.amount, d.brief, d.detail, d.policy.how(),
                    d.ask, effect, d.policy.notice(), d.policy.sources()));
        }
        List<Item> sorted = new ArrayList<>(items);   // List.sort는 안정 정렬이라 같은 값이면 위 순서 그대로
        sorted.sort(Comparator.comparingInt((Item it) -> it.status().ordinal())
                .thenComparing((Item it) -> it.effect() == null ? BigDecimal.ZERO : it.effect().diff(), Comparator.reverseOrder()));
        Long refund = sim.tax() == null || sim.tax().refundPerYear() <= 0 ? null : sim.tax().refundPerYear();
        return new BenefitsResponse(rules.getAsOf(), base, refund, sorted);
    }

    // ===== 정책별 판정 =====

    /** 청년미래적금: 대상 여부 · 지원금은 /simulate 값(경고 · risks.policySaving) 그대로 */
    private void youthFuture(Ctx c, List<Draft> out) {
        Policy p = rules.policy("youthFuture");
        var saving = c.sim.risks().policySaving();
        Warning ineligible = c.warning("INFO_POLICY_INELIGIBLE");
        if (!saving.eligible()) {
            out.add(new Draft("youthFuture", p, Status.NO, null, ineligible != null ? ineligible.message() : p.text("ineligible")).brief(p.brief("ineligible")));
            return;
        }
        if (c.warning("INFO_POLICY_NO_MATCH") != null) {
            out.add(new Draft("youthFuture", p, Status.NO, null, p.text("noMatch")).brief(p.brief("noMatch")));
            return;
        }
        Long subsidy = saving.expectedSubsidy();
        if (subsidy == null || subsidy <= 0) {
            out.add(new Draft("youthFuture", p, Status.CHECK, null, p.text("notPutting")).brief(p.brief("notPutting")));
            return;
        }
        out.add(new Draft("youthFuture", p, Status.YES, "약 " + won(subsidy), p.text("yes")).brief(p.brief("yes")));

        // 우대형: 정부 지원금이 두 배 (엔진은 일반형 6%만 계산해서 효과는 금액으로만 보여 준다)
        Policy pr = rules.policy("youthFuturePreferential");
        String extra = "약 " + won(subsidy) + " 더";
        Boolean sme = c.e.sme();
        if (Boolean.FALSE.equals(sme)) {
            out.add(new Draft("youthFuturePreferential", pr, Status.NO, null, pr.text("notSme")).brief(pr.brief("notSme")));
        } else if (sme == null) {
            out.add(new Draft("youthFuturePreferential", pr, Status.CHECK, extra,
                    pr.text("ask").replace("{extra}", won(subsidy)), List.of("sme")).brief(pr.brief("ask")));
        } else if (c.gross <= pr.num("incumbentMaxGross")) {
            out.add(new Draft("youthFuturePreferential", pr, Status.CHECK, extra, pr.text("incumbent").replace("{extra}", won(subsidy))).brief(pr.brief("incumbent")));
        } else if (c.gross <= pr.num("newHireMaxGross")) {
            out.add(new Draft("youthFuturePreferential", pr, Status.CHECK, null, pr.text("newHire")).brief(pr.brief("newHire")));
        } else {
            out.add(new Draft("youthFuturePreferential", pr, Status.NO, null, pr.text("overGross")).brief(pr.brief("overGross")));
        }
    }

    /** 청년월세 지원 (국토교통부 2026 매뉴얼) */
    private Draft youthRent(Ctx c) {
        Policy p = rules.policy("youthRent");
        String key = "youthRent";
        YearMonth payStart = later(YearMonth.parse(p.assumedStart()), c.start);
        int ageAtApply = c.age + (int) (ChronoUnit.MONTHS.between(c.start, payStart) / 12);
        if (c.age < p.num("minAge") || ageAtApply > p.num("maxAge")) return new Draft(key, p, Status.NO, null, p.text("age")).brief(p.brief("age"));
        if (!c.homeless) return new Draft(key, p, Status.NO, null, p.text("notHomeless")).brief(p.brief("notHomeless"));
        if (Boolean.FALSE.equals(c.e.livingApart())) return new Draft(key, p, Status.NO, null, p.text("notApart")).brief(p.brief("notApart"));

        // 본인 소득: 세전 월급에서 근로소득 30%를 뺀 소득평가액 ≤ 중위 60%
        double evaluated = c.gross / 12 * (1 - p.num("earnedIncomeDeduction"));
        if (evaluated > p.num("ownIncomeLimit")) {
            long limit = Math.round(p.num("ownIncomeLimit") / (1 - p.num("earnedIncomeDeduction")));
            return new Draft(key, p, Status.NO, null, p.text("income").replace("{limit}", won(limit)))
                    .brief(p.brief("income").replace("{limit}", won(limit)));
        }
        long assets = c.input.assets().cash() + c.input.assets().deposits() + c.input.assets().investments()
                + (c.e.rentDeposit() == null ? 0 : c.e.rentDeposit());
        if (assets > p.num("ownAssetLimit")) return new Draft(key, p, Status.NO, null, p.text("asset")).brief(p.brief("asset"));

        boolean parentsMatter = c.age < p.num("parentsCheckUnderAge") && evaluated < p.num("parentsExemptIncome");
        if (parentsMatter && Boolean.FALSE.equals(c.e.parentsIncomeLow())) return new Draft(key, p, Status.NO, null, p.text("parents")).brief(p.brief("parents"));

        long rent = c.input.expenses().monthlyRent() == null ? 0 : c.input.expenses().monthlyRent();
        List<String> ask = new ArrayList<>();
        List<String> why = new ArrayList<>();
        if (c.e.livingApart() == null) { ask.add("livingApart"); why.add(p.text("askApart")); }
        if (c.e.rentDeposit() == null) { ask.add("rentDeposit"); why.add(p.text("askDeposit")); }
        if (parentsMatter && c.e.parentsIncomeLow() == null) { ask.add("parentsIncomeLow"); why.add(p.text("askParents")); }
        if (rent <= 0) why.add(0, p.text("noRent"));

        int months = (int) p.num("months");
        long monthly = rent <= 0 ? (long) p.num("monthlyMax") : Math.min(rent, (long) p.num("monthlyMax"));
        String amount = rent <= 0 ? "월 최대 " + won(monthly) + " × " + months + "개월"
                : "월 " + won(monthly) + " × " + months + "개월 (최대 " + won(monthly * months) + ")";
        why.add(p.text("yes"));
        Status status = ask.isEmpty() && rent > 0 ? Status.YES : Status.CHECK;
        Draft d = new Draft(key, p, status, amount, String.join(" ", why), ask)
                .brief(p.brief(rent <= 0 ? "noRent" : status == Status.YES ? "yes" : "check"));
        if (rent > 0) d.flow = new Flow(payStart, months, monthly, p.text("assumption"));
        return d;
    }

    /** 중소기업 취업자 소득세 감면 (조세특례제한법 제30조) */
    private Draft smeTaxRelief(Ctx c) {
        Policy p = rules.policy("smeTaxRelief");
        String key = "smeTaxRelief";
        if (Boolean.TRUE.equals(c.e.taxReliefNow())) return new Draft(key, p, Status.YES, null, p.text("already")).brief(p.brief("already"));
        if (Boolean.FALSE.equals(c.e.sme())) return new Draft(key, p, Status.NO, null, p.text("notSme")).brief(p.brief("notSme"));

        YearMonth deadline = YearMonth.parse(p.hireDeadline());
        YearMonth hire = c.e.hireMonth() == null ? null : YearMonth.parse(c.e.hireMonth());
        List<String> ask = new ArrayList<>();
        if (hire != null && hire.isAfter(deadline)) return new Draft(key, p, Status.NO, null, p.text("deadline")).brief(p.brief("deadline"));
        if (hire == null && c.start.isAfter(deadline)) ask.add("hireMonth");
        int years = (int) p.num("years");
        YearMonth from = hire == null ? c.start : hire;
        YearMonth end = from.plusYears(years);
        if (!end.isAfter(c.start)) return new Draft(key, p, Status.NO, null, p.text("ended")).brief(p.brief("ended"));

        // 입사할 때 나이 (군 복무는 최대 6년까지 빼 준다)
        int ageAtHire = hire == null || !hire.isBefore(c.start) ? c.age : c.age - (int) (ChronoUnit.MONTHS.between(hire, c.start) / 12);
        int military = c.e.militaryMonths() == null ? 0 : Math.min(c.e.militaryMonths(), (int) p.num("militaryMaxYears") * 12);
        if (ageAtHire - military / 12.0 >= p.num("maxAge") + 1) {
            if (c.e.militaryMonths() == null) {
                return new Draft(key, p, Status.CHECK, null, p.text("askMilitary"), List.of("militaryMonths")).brief(p.brief("askMilitary"));
            }
            return new Draft(key, p, Status.NO, null, p.text("age")).brief(p.brief("age"));
        }

        double decided = WageTax.decided(paramsRepository.getEngineParams().insurance(), c.gross);
        long yearly = Math.round(Math.min(decided * p.num("rate"), p.num("annualCap")) * (1 + p.num("localTaxRate")));
        long monthly = Math.round(yearly / 12.0);
        if (c.e.sme() == null) ask.add(0, "sme");
        if (hire == null && !ask.contains("hireMonth")) ask.add("hireMonth");
        Status status = c.e.sme() == null ? Status.CHECK : Status.YES;
        String detail = (c.e.sme() == null ? p.text("askSme") : p.text("yes")) + " " + p.text("exclusions") + " " + p.text("estimate");
        String amount = yearly <= 0 ? "내는 소득세가 거의 없어요" : "연 약 " + won(yearly) + " (월 약 " + won(monthly) + ")";
        Draft d = new Draft(key, p, status, amount, detail, ask).brief(p.brief(c.e.sme() == null ? "askSme" : "yes"));
        YearMonth payFrom = later(from, c.start);
        int months = (int) ChronoUnit.MONTHS.between(payFrom, end);
        if (monthly > 0 && months > 0) {
            d.flow = new Flow(payFrom, months, monthly, hire == null ? "지금 입사했다고 보고 5년을 셌어요." : null);
        }
        return d;
    }

    private Draft rentCredit(Ctx c) {
        Policy p = rules.policy("rentCredit");
        String key = "rentCredit";
        if (!c.homeless) return new Draft(key, p, Status.NO, null, p.text("notHomeless")).brief(p.brief("notHomeless"));
        if (c.gross > p.num("maxGross")) return new Draft(key, p, Status.NO, null, p.text("overGross").replace("{gross}", c.grossName())).brief(p.brief("overGross"));
        long rent = c.input.expenses().monthlyRent() == null ? 0 : c.input.expenses().monthlyRent();
        return rent > 0 ? new Draft(key, p, Status.YES, "연말정산에 포함", p.text("yes")).brief(p.brief("yes"))
                : new Draft(key, p, Status.CHECK, null, p.text("noRent")).brief(p.brief("noRent"));
    }

    private Draft housingDeduction(Ctx c) {
        Policy p = rules.policy("housingDeduction");
        String key = "housingDeduction";
        if (!c.homeless) return new Draft(key, p, Status.NO, null, p.text("notHomeless")).brief(p.brief("notHomeless"));
        if (c.gross > p.num("maxGross")) return new Draft(key, p, Status.NO, null, p.text("overGross").replace("{gross}", c.grossName())).brief(p.brief("overGross"));
        return c.housingMonthly() > 0 ? new Draft(key, p, Status.YES, "연말정산에 포함", p.text("yes")).brief(p.brief("yes"))
                : new Draft(key, p, Status.CHECK, null, p.text("notPutting")).brief(p.brief("notPutting"));
    }

    private Draft pensionCredit(Ctx c) {
        Policy p = rules.policy("pensionCredit");
        return c.pensionMonthly() > 0 ? new Draft("pensionCredit", p, Status.YES, "연말정산에 포함", p.text("yes")).brief(p.brief("yes"))
                : new Draft("pensionCredit", p, Status.CHECK, null, p.text("notPutting")).brief(p.brief("notPutting"));
    }

    /** K-패스: 누구나 받는다. 교통비를 알면 돌려받는 돈을 계산에 넣는다 (한 달 15번 이상 탄다고 본다) */
    private Draft kpass(Ctx c) {
        Policy p = rules.policy("kpass");
        boolean youth = c.age >= p.num("youthMinAge") && c.age <= p.num("youthMaxAge");
        double rate = youth ? p.num("youthRate") : p.num("generalRate");
        String detail = p.text(youth ? "youth" : "general");
        Long transit = c.e.monthlyTransit();
        if (transit == null) {
            return new Draft("kpass", p, Status.YES, "교통비의 " + Math.round(rate * 100) + "%",
                    detail + " " + p.text("askTransit"), List.of("monthlyTransit")).brief(p.brief(youth ? "youth" : "general"));
        }
        long monthly = Math.round(transit * rate);
        Draft d = new Draft("kpass", p, Status.YES, "월 약 " + won(monthly), detail).brief(p.brief(youth ? "youth" : "general"));
        if (monthly > 0) d.flow = new Flow(c.start, null, monthly, "한 달에 15번 이상 탄다고 보고 계속 받는다고 계산했어요.");
        return d;
    }

    /** 청년내일저축계좌: 2026년부터 가구 소득 중위 50% 이하만 새로 가입. 못 받아요는 혼자 살고 소득이 분명히 넘을 때만 */
    private Draft naeil(Ctx c) {
        Policy p = rules.policy("naeil");
        if (c.age < p.num("minAge") || c.age > p.num("maxAge")) return new Draft("naeil", p, Status.NO, null, p.text("age")).brief(p.brief("age"));
        boolean alone = Boolean.TRUE.equals(c.e.livingApart());
        if (alone) {
            double evaluated = Math.max(0, c.gross / 12 - p.num("conservativeExtraDeduction")) * (1 - p.num("earnedIncomeDeduction"));
            if (evaluated > rules.getMedianOnePerson() * 0.5) return new Draft("naeil", p, Status.NO, null, p.text("overIncome")).brief(p.brief("overIncome"));
        }
        return new Draft("naeil", p, Status.CHECK, p.text("amount"), p.text(alone ? "checkAlone" : "check") + " " + p.text("amountDetail"),
                c.e.livingApart() == null ? List.of("livingApart") : List.of()).brief(p.brief(alone ? "checkAlone" : "check"));
    }

    private Draft beotimmok(Ctx c) {
        Policy p = rules.policy("beotimmok");
        if (c.age < p.num("minAge") || c.age > p.num("maxAge")) return new Draft("beotimmok", p, Status.NO, null, p.text("age")).brief(p.brief("age"));
        if (!c.homeless) return new Draft("beotimmok", p, Status.NO, null, p.text("notHomeless")).brief(p.brief("notHomeless"));
        if (c.gross > p.num("incomeLimit")) return new Draft("beotimmok", p, Status.NO, null, p.text("overIncome").replace("{gross}", c.grossName())).brief(p.brief("overIncome"));
        return new Draft("beotimmok", p, Status.CHECK, p.text("amount"), p.text("check")).brief(p.brief("check"));
    }

    /** 국민취업지원제도: 지금 받는 돈이 아니라 실직하면 신청하는 것 */
    private Draft employmentSupport(Ctx c) {
        Policy p = rules.policy("employmentSupport");
        boolean youth = c.age <= p.num("youthMaxAge");
        String amount = youth ? "월 " + won((long) p.num("monthly")) + " × " + (int) p.num("months") + "개월" : null;
        return new Draft("employmentSupport", p, Status.LATER, amount, p.text(youth ? "youth" : "general"))
                .brief(p.brief(youth ? "youth" : "general"));
    }

    // ===== 공통 =====

    private static YearMonth later(YearMonth a, YearMonth b) {
        return a.isBefore(b) ? b : a;
    }

    /** 원 → "51만 원" / "1억 2,000만 원" / 10만 원 미만은 100원 단위 "18,000원" */
    static String won(long won) {
        NumberFormat f = NumberFormat.getIntegerInstance(Locale.KOREA);
        if (Math.abs(won) < 100_000) return f.format(Math.round(won / 100.0) * 100) + "원";
        long man = Math.round(won / 10_000.0);
        long eok = man / 10_000;
        long rest = man % 10_000;
        if (eok == 0) return f.format(man) + "만 원";
        return rest == 0 ? eok + "억 원" : eok + "억 " + f.format(rest) + "만 원";
    }

    /** 판정 재료: 바꾼 계획을 적용한 입력, 그 계산 결과, 선택 질문 */
    private final class Ctx {
        final SimulationInput input;
        final SimulateResponse sim;
        final Eligibility e;
        final YearMonth start;
        final int age;
        final boolean homeless;
        final double gross;
        final boolean grossEstimated;

        Ctx(SimulationInput input, SimulateResponse sim, Eligibility e) {
            this.input = input;
            this.sim = sim;
            this.e = e;
            this.start = YearMonth.parse(input.startMonth());
            this.age = input.person().age();
            this.homeless = !Boolean.FALSE.equals(input.person().homelessHouseholder());
            Long g = input.income().annualGrossSalary();
            this.grossEstimated = g == null;
            this.gross = g != null ? g : input.income().monthlyNetIncome() * 12 / rules.getNetToGrossRatio();
        }

        String grossName() {
            return grossEstimated ? "총급여(어림)" : "총급여";
        }

        Warning warning(String code) {
            return sim.warnings() == null ? null : sim.warnings().stream().filter(w -> code.equals(w.code())).findFirst().orElse(null);
        }

        long housingMonthly() {
            return sim.tax() == null || sim.tax().allocation() == null ? 0 : sim.tax().allocation().housingMonthly();
        }

        long pensionMonthly() {
            return sim.tax() == null || sim.tax().allocation() == null ? 0 : sim.tax().allocation().pensionMonthly();
        }
    }

    private static final class Draft {
        final String key;
        final Policy policy;
        final Status status;
        final String amount;
        final String detail;
        final List<String> ask;
        String brief;
        Flow flow;

        Draft(String key, Policy policy, Status status, String amount, String detail) {
            this(key, policy, status, amount, detail, List.of());
        }

        Draft(String key, Policy policy, Status status, String amount, String detail, List<String> ask) {
            this.key = key;
            this.policy = policy;
            this.status = status;
            this.amount = amount;
            this.detail = detail;
            this.ask = List.copyOf(ask);
        }

        /** 카드에 바로 보이는 짧은 한 줄 (benefits.json brief) */
        Draft brief(String text) {
            this.brief = text;
            return this;
        }
    }

    /** 정책으로 들어오는 돈: from부터 months개월 동안 매달 monthly원 (months가 null이면 끝없이) */
    private record Flow(YearMonth from, Integer months, long monthly, String assumption) {

        List<Event> events(String name) {
            List<Event> out = new ArrayList<>();
            String label = name.length() > 20 ? name.substring(0, 20) : name;
            out.add(spend(label, from, -monthly));
            if (months != null) out.add(spend(label, from.plusMonths(months), monthly));
            return out;
        }

        private static Event spend(String name, YearMonth at, long change) {
            return new Event(PresetKey.CUSTOM, name, at.toString(), EventType.SPEND_CHANGE, null, change, null, null, null);
        }
    }
}
