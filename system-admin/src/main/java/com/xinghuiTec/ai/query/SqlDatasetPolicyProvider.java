package com.xinghuiTec.ai.query;

import java.util.List;

/** Server code only: business modules register documented ownership policies as Spring beans. */
public interface SqlDatasetPolicyProvider {
    List<SqlDatasetRegistry.TablePolicy> policies();
}
