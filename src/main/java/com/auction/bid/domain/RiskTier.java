package com.auction.bid.domain;

import java.util.Arrays;

public enum RiskTier {
    NORMAL("normal"),
    HOT("hot");

    private final String value;

    RiskTier(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static RiskTier fromValue(String value) {
        return Arrays.stream(values())
                .filter(t -> t.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("risk_tier desconhecido: " + value));
    }
}
