package com.auction.bid.domain;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;

public enum Decision {
    ACEITO("aceito"),
    REJEITADO("rejeitado");

    private final String value;

    Decision(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    public static Decision fromValue(String value) {
        return Arrays.stream(values())
                .filter(d -> d.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("decision desconhecida: " + value));
    }
}
