package com.xinghuiTec.ai.contract;

/** Scene takeover has one writer. Implemented by B using mapping metadata, with no C dependency. */
public interface AiSceneOwnership {
    boolean ownsApplication(String applicationId);
    boolean ownsTemplate(String templateId);
    void requireApplicationWritable(String applicationId);
    void requireTemplateWritable(String templateId);
}
