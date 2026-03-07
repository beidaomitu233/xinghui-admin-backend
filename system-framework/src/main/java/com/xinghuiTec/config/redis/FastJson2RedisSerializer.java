package com.xinghuiTec.config.redis;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONReader;
import com.alibaba.fastjson2.JSONWriter.Feature;
import java.nio.charset.Charset;
import java.util.AbstractMap.SimpleEntry;
import java.util.Map;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;

/**
 * Redis序列化器，使用FastJSON2实现
 * 用于在Redis中存储和读取Java对象，支持对象的序列化和反序列化操作
 * 
 * @param <T> 泛型参数，表示需要序列化的对象类型
 */
public class FastJson2RedisSerializer<T> implements RedisSerializer<T> {

    /**
     * 需要序列化的对象类型
     */
    private final Class<T> clazz;

    /**
     * 序列化对象为字节数组
     * 将Java对象转换为字节流，以便存储到Redis中
     *
     * @param t 需要序列化的对象，可以为{@literal null}
     * @return 序列化后的字节数组
     * @throws SerializationException 序列化过程中发生错误时抛出
     */
    @Override
    public byte[] serialize(T t) throws SerializationException {
        if (t == null) {
            return new byte[0];
        }
        Map.Entry<String, T> entity = new SimpleEntry<>(t.getClass().getName(), t);
        return JSON.toJSONString(entity, Feature.WriteClassName).getBytes(Charset.defaultCharset());
    }

    /**
     * 反序列化字节数组为对象
     * 将从Redis中读取的字节流转换回Java对象
     *
     * @param bytes 对象的二进制表示形式，可以为{@literal null}
     * @return 反序列化后的对象
     * @throws SerializationException 反序列化过程中发生错误时抛出
     */
    @Override
    public T deserialize(byte[] bytes) throws SerializationException {
        if (bytes == null) {
            return null;
        }
        String str = new String(bytes, Charset.defaultCharset());
        int index = str.indexOf(":");
        String cls = str.substring(2, index - 1);
        String obj = str.substring(index + 1, str.length() - 1);
        return JSON.parseObject(
                obj,
                clazz,
                JSONReader.autoTypeFilter(
                        cls
                ),
                JSONReader.Feature.SupportClassForName);
    }

    /**
     * 构造函数
     * 初始化FastJson2RedisSerializer实例
     *
     * @param clazz 需要序列化的对象类型
     */
    public FastJson2RedisSerializer(Class<T> clazz) {
        super();
        this.clazz = clazz;
    }
}