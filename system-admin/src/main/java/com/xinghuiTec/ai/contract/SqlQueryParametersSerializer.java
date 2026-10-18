package com.xinghuiTec.ai.contract;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Map;

/** Typed decimal envelope prevents browser JSON.parse rounding saved query parameters. Strings stay strings. */
public final class SqlQueryParametersSerializer extends JsonSerializer<Map<String,Object>> {
    @Override public void serialize(Map<String,Object> values,JsonGenerator output,SerializerProvider provider)throws IOException {
        output.writeStartObject();
        for(var entry:values.entrySet()) {
            output.writeFieldName(entry.getKey());Object value=entry.getValue();
            if(value instanceof Number number) {
                output.writeStartObject();output.writeStringField("$number",new BigDecimal(number.toString()).toPlainString());output.writeEndObject();
            }else output.writeObject(value);
        }
        output.writeEndObject();
    }
}
