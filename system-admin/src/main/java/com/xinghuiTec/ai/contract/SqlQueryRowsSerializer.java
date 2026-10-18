package com.xinghuiTec.ai.contract;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;

/** Public query previews show exact numeric text; internal rows retain Number for model data. */
public final class SqlQueryRowsSerializer extends JsonSerializer<List<Map<String,Object>>> {
    @Override public void serialize(List<Map<String,Object>> rows,JsonGenerator output,SerializerProvider provider)throws IOException {
        output.writeStartArray();
        for(var row:rows) {
            output.writeStartObject();
            for(var entry:row.entrySet()) {
                output.writeFieldName(entry.getKey());Object value=entry.getValue();
                if(value instanceof Number number)output.writeString(new BigDecimal(number.toString()).toPlainString());
                else output.writeObject(value);
            }
            output.writeEndObject();
        }
        output.writeEndArray();
    }
}
