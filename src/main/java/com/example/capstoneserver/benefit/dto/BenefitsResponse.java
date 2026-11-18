package com.example.capstoneserver.benefit.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 받을 수 있는 혜택 목록. 받아요(YES) → 확인해 보세요(CHECK) → 실직하면(LATER) → 못 받아요(NO) 순서,
 * 같은 상태 안에서는 목표 달성 가능성을 더 올리는 것이 위.
 *
 * @param probability   지금(바꾼) 계획의 목표 달성 가능성 % (/simulate의 goal.probability와 같다)
 * @param refundPerYear 연말정산으로 매년 돌려받는 돈 (/simulate의 tax.refundPerYear, 없으면 null)
 */
public record BenefitsResponse(String asOf, BigDecimal probability, Long refundPerYear, List<Item> items) {

    public enum Status { YES, CHECK, LATER, NO }

    public enum Category { CASH, TAX, SAVING, LOAN, SAFETY }

    /**
     * @param amount  받는 돈 한 줄 (모르면 null)
     * @param summary 카드에 바로 보이는 짧은 한 줄 (판정 이유). detail은 '자세히'를 눌러야 보인다
     * @param ask    더 알려 주면 판정이 정확해지는 질문 (Eligibility 필드 이름)
     * @param effect 받으면 목표 달성 가능성이 어떻게 바뀌는지 (계산할 수 없으면 null)
     * @param notice 2027년에 바뀔 수 있는 내용 (확정 전)
     */
    public record Item(String key, String name, Category category, Status status, String amount, String summary, String detail,
                       String how, List<String> ask, Effect effect, String notice, List<Source> sources) {}

    /** @param assumption 계산에 넣은 가정 한 줄 (없으면 null) */
    public record Effect(BigDecimal probability, BigDecimal diff, String assumption) {}

    public record Source(String title, String url) {}
}
