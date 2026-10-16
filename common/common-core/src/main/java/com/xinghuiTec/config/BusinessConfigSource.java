package com.xinghuiTec.config;

import java.util.Map;
import java.util.Optional;

/** Internal runtime contract. Secrets must never be returned by administration APIs. */
public interface BusinessConfigSource {
    record Snapshot(String section, boolean enabled, int revision, Map<String,String> parameters, Map<String,String> secrets) {
        @Override public String toString() { return "BusinessConfigSnapshot[section="+section+",revision="+revision+",secrets=REDACTED]"; }
        public String platform() { return "business-oss-"+revision; }
    }
    Optional<Snapshot> current(String section);
    Optional<Snapshot> historical(String section, int revision);
}
