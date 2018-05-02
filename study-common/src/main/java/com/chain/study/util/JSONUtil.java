/*
 * Copyright (C), 2002-2014, 苏宁易购电子商务有限公司
 * FileName: JSONUtil.java
 * Author:   13120645
 * Date:     2014-2-20
 * Description: //模块目的、功能描述      
 * History: //修改记录
 * <author>      <time>      <version>    <desc>
 * 修改人姓名             修改时间            版本号                  描述
 */
package com.chain.study.util;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * 〈JSON工具类〉<br>
 * 〈将JSON格式转换为具体的javabean，或者将javabean转换为JSON字符串〉
 * 
 * @author 13120645
 * @since V1.0
 */
public class JSONUtil {

    /**
     * 日志
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(JSONUtil.class);

    private static final TypeReference<Map<String, Object>> STRING_MAP_TYPE_REF = new TypeReference<Map<String, Object>>() {
    };

    /**
     * 功能描述: <br>
     * 〈json转换成javaBean〉
     * 
     * @param json JSON字符串
     * @param clazz javabean类
     * @return
     * @see [相关类/方法](可选)
     * @since [产品/模块版本](可选)
     */
    public static final <T> T parseJSONToObject(String json, Class<T> clazz) {
        try {
            return JSON.parseObject(json, clazz);
        } catch (Exception e) {
            LOGGER.error("字符串解析为JSONObject失败, text: " + json + "；clazz: " + clazz.toString(), e);
        }

        return null;
    }

    /**
     * 功能描述: <br>
     * 〈javabean转换为json〉
     * 
     * @param object
     * @return
     * @see [相关类/方法](可选)
     * @since [产品/模块版本](可选)
     */
    public static final String toJSONString(Object object) {
        return JSON.toJSONString(object);
    }

    /**
     * string解析为JSON对象
     *
     * @param text 待解析的字符串
     * @return JSON对象
     * @author 13010146
     * @since v20160629
     */
    public static final JSONObject parseObject(String text) {
        try {
            return JSON.parseObject(text);
        } catch (Exception e) {
            LOGGER.error("字符串解析为JSONObject失败, text: " + text, e);
        }

        return null;
    }

    /**
     * string解析为JSONArray对象
     *
     * @param text 待解析的字符串
     * @return JSON对象
     * @author 13010146
     * @since v20160629
     */
    public static final JSONArray parseArray(String text) {
        try {
            return JSON.parseArray(text);
        } catch (Exception e) {
            LOGGER.error("字符串解析为JSONArray失败, text: " + text, e);
        }

        return null;
    }

    /**
     * 功能描述: JSON转换为map<br>
     * 
     * @param jsonStr
     * @return
     * @since
     */
    public static final Map<String, Object> jsonToStringMap(String jsonStr) {
        try {
            return JSON.parseObject(jsonStr, STRING_MAP_TYPE_REF);
        } catch (Exception e) {
            LOGGER.error("字符串解析为Map<String, Object>失败, text: " + jsonStr, e);
        }

        return null;
    }

    /**
     * 功能描述: obj转换为map<br>
     * 
     * @param jsonStr
     * @return
     * @since
     */
    public static final Map<String, Object> objToStringMap(Object object) {
        String json = toJSONString(object);
        return JSON.parseObject(json, STRING_MAP_TYPE_REF);
    }


    /**
     * 功能描述: 将json string转成 list<object><br>
     * 
     * @param json
     * @param T
     * @return
     * @see
     * @since(可选)
     */
    public static <T> List<T> parseJSONToList(String json, Class<T> T) {
        try {
            return JSONArray.parseArray(json, T);
        } catch (Exception e) {
            LOGGER.error("字符串解析为List<T>失败, text: " + json + "；class: " + T.toString(), e);
        }

        return null;
    }

    /**
     * 功能描述: <br>
     * 〈Javabean转JSON对象〉
     *
     * @param object
     * @return JSON对象
     * @author 16050263
     * @since v20160829
     */
    public static Object toJSON(Object object) {
        try {
            return JSON.toJSON(object);
        } catch (Exception e) {
            LOGGER.error("Javabean转Json对象失败，object：{}", object, e);
        }

        return null;
    }

}