package com.xinghuiTec.ai.contract;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.util.*;

/** Exact scalar parameters survive scene snapshot JSON with any caller's ObjectMapper settings. */
public final class SqlQueryParametersDeserializer extends JsonDeserializer<Map<String,Object>> {
    @Override public Map<String,Object> deserialize(JsonParser parser,DeserializationContext context)throws IOException {
        if(!parser.isExpectedStartObjectToken())throw JsonMappingException.from(parser,"Invalid SQL parameters");
        var result=new LinkedHashMap<String,Object>();
        while(parser.nextToken()!=JsonToken.END_OBJECT){
            if(parser.currentToken()!=JsonToken.FIELD_NAME)throw JsonMappingException.from(parser,"Invalid SQL parameters");
            String name=parser.currentName();if(result.containsKey(name)||result.size()>=32)throw JsonMappingException.from(parser,"Invalid SQL parameters");
            var token=parser.nextToken();Object value;
            if(token==JsonToken.VALUE_NULL)value=null;
            else if(token==JsonToken.VALUE_STRING)value=parser.getText();
            else if(token==JsonToken.VALUE_TRUE||token==JsonToken.VALUE_FALSE)value=token==JsonToken.VALUE_TRUE;
            else if(token==JsonToken.VALUE_NUMBER_INT||token==JsonToken.VALUE_NUMBER_FLOAT)value=parser.getDecimalValue();
            else if(token==JsonToken.START_OBJECT) {
                if(parser.nextToken()!=JsonToken.FIELD_NAME||!"$number".equals(parser.currentName())||parser.nextToken()!=JsonToken.VALUE_STRING)
                    throw JsonMappingException.from(parser,"Invalid exact SQL number");
                String text=parser.getText();
                if(text.length()>128||!text.matches("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?"))throw JsonMappingException.from(parser,"Invalid exact SQL number");
                value=new java.math.BigDecimal(text);
                if(parser.nextToken()!=JsonToken.END_OBJECT)throw JsonMappingException.from(parser,"Invalid exact SQL number");
            }
            else throw JsonMappingException.from(parser,"Invalid SQL parameters");
            result.put(name,value);
        }
        return result;
    }
}
