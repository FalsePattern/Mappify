package com.falsepattern.mappify;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class StringPair {
    public final String from;
    public final String to;
    public String map(String x) {
        return x.replace(from, to);
    }
}
