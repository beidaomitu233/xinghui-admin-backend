package com.xinghuiTec.ai.scene;

import java.util.List;

/** Only fixed safe codes/paths and authorized revision metadata may cross HTTP. */
public final class SceneFailure extends RuntimeException {
    private final int status;
    private final SceneDtos.ErrorDetails details;
    public SceneFailure(int status,String code) {this(status,code,null);}
    public SceneFailure(int status,String code,SceneDtos.ErrorDetails details) {
        super(code);this.status=status;this.details=details;
    }
    public int status(){return status;}
    public SceneDtos.ErrorDetails details(){return details;}
    static SceneFailure field(String path,String code) {
        return new SceneFailure(400,"INVALID_SCENE_CONFIGURATION",new SceneDtos.ErrorDetails(List.of(new SceneDtos.FieldError(path,code)),null,null,null));
    }
    static void require(boolean valid,int status,String code) {if(!valid)throw new SceneFailure(status,code);}
}
