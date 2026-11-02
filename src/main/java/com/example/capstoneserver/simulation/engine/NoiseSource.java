package com.example.capstoneserver.simulation.engine;

import java.util.Arrays;

/**
 * 경로별 난수 (구현 명세서 4장). 운영은 sfc32, 확정 계산은 난수 0, 검증은 파일에서 주입한다.
 * 순서대로 뽑으므로 T가 다르면 값도 다르다. 엔진 호출마다 그 호출의 T로 다시 만든다.
 */
@FunctionalInterface
public interface NoiseSource {

    PathNoise noise(int p, int t);

    record PathNoise(double[] z1, double[] z2, double[] uOnset, double[] uDur, double[] uInvol, double[] uShock, double[] uAmt) {}

    /** engine.js의 randomNoise와 1:1. 정규분포는 짝 경로끼리 부호만 반대 (대조 변량) */
    static NoiseSource sfc32(int seed, int n) {
        int half = n % 2 == 0 ? n / 2 : n;
        return (p, t) -> {
            int years = t / 12 + 1;
            double sign = (n % 2 == 0 && p >= half) ? -1 : 1;
            Sfc32 rn = Sfc32.of(seed, p % half, 1);
            Sfc32 ru = Sfc32.of(seed, p, 2);

            double[] z1 = new double[t];
            double[] z2 = new double[years];
            for (int j = 0; j < t; j++) z1[j] = sign * gauss(rn);
            for (int j = 0; j < years; j++) z2[j] = sign * gauss(rn);
            return new PathNoise(z1, z2, uniform(ru, t), uniform(ru, t), uniform(ru, t), uniform(ru, t), uniform(ru, t));
        };
    }

    /** 확정 계산: 실직·급전이 없고 물가·임금은 평균값으로 간다 */
    static NoiseSource zero() {
        return (p, t) -> new PathNoise(new double[t], new double[t / 12 + 1], filled(t, 1), new double[t], filled(t, 1),
                filled(t, 1), new double[t]);
    }

    private static double gauss(Sfc32 r) {
        double u = 0;
        while (u == 0) u = r.next();
        return StrictMath.sqrt(-2 * StrictMath.log(u)) * StrictMath.cos(2 * Math.PI * r.next());
    }

    private static double[] uniform(Sfc32 r, int length) {
        double[] a = new double[length];
        for (int j = 0; j < length; j++) a[j] = r.next();
        return a;
    }

    private static double[] filled(int length, double value) {
        double[] a = new double[length];
        Arrays.fill(a, value);
        return a;
    }

    /** JS 난수 생성기를 비트 단위로 옮긴 것. 그래야 API 명세 예시 값과 정확히 같아진다 */
    final class Sfc32 {
        private int a;
        private int b;
        private int c;
        private int d;

        Sfc32(int a, int b, int c, int d) {
            this.a = a;
            this.b = b;
            this.c = c;
            this.d = d;
        }

        double next() {
            int t = a + b + d;
            d = d + 1;
            a = b ^ (b >>> 9);
            b = c + (c << 3);
            c = (c << 21) | (c >>> 11);
            c = c + t;
            return (t & 0xFFFFFFFFL) / 4294967296.0;
        }

        static int mix(int seed, int k, int s) {
            int h = seed ^ 0x9e3779b9;
            for (int v : new int[] {k, s}) {
                h = (h ^ (v + 0x7f4a7c15)) * 0x85ebca6b;
                h ^= h >>> 13;
                h = h * 0xc2b2ae35;
                h ^= h >>> 16;
            }
            return h;
        }

        static Sfc32 of(int seed, int k, int s) {
            Sfc32 r = new Sfc32(mix(seed, k, s), mix(seed, k, s + 11), mix(seed, k, s + 23), mix(seed, k, s + 37));
            for (int i = 0; i < 12; i++) r.next();   // 처음 12개는 버린다
            return r;
        }
    }
}
