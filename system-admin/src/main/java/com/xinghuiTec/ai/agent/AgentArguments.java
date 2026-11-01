package com.xinghuiTec.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.xinghuiTec.agent.api.AgentProblem;
import java.util.*;

final class AgentArguments {
    private AgentArguments(){}
    static void object(JsonNode value){if(value==null||!value.isObject())throw invalid("操作参数必须是对象。");}
    static void only(JsonNode value,String... names){object(value);var allowed=Set.of(names);value.fieldNames().forEachRemaining(name->{if(!allowed.contains(name))throw invalid("存在未定义的参数，请按资源定义填写。");});}
    static String text(JsonNode value,String key,int limit,boolean required){
        var field=value.get(key);if(field==null||field.isNull()){if(required)throw invalid("缺少参数："+key);return null;}
        if(!field.isTextual()||field.textValue().length()>limit||required&&field.textValue().isBlank())throw invalid("参数格式不正确："+key);return field.textValue();
    }
    static long id(JsonNode value,String key){String id=text(value,key,19,true);return id(id);}
    static long id(String value){try{if(value==null||!value.matches("[1-9][0-9]{0,18}"))throw new IllegalArgumentException();return Long.parseLong(value);}catch(RuntimeException invalid){throw invalid("记录ID必须是正整数字符串。");}}
    static int integer(JsonNode value,String key,int fallback,int min,int max){var field=value.get(key);if(field==null||field.isNull())return fallback;if(!field.isIntegralNumber()||!field.canConvertToInt()||field.intValue()<min||field.intValue()>max)throw invalid("整数参数超出范围："+key);return field.intValue();}
    static int revision(JsonNode value,String key){int result=integer(value,key,-1,0,Integer.MAX_VALUE);if(result<0)throw invalid("请先查询当前版本，并提供参数："+key);return result;}
    static AgentProblem invalid(String message){return new AgentProblem("INVALID_ARGUMENTS",message);}
}
