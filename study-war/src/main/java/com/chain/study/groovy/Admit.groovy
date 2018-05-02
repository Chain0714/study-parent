package com.chain.study.groovy

import com.chain.study.container.BaseGroovyInterface
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

/**
 * Created by 17032651 on 2017/6/22.
 */
class Admit implements BaseGroovyInterface{
    Map<String, Object> run(Map<String, Object> params) {
        String json = params.get("json");
        def root = new JsonSlurper().parseText(json)
        if(root.age<18){
            root.isAdmit="1"
        }
        return ["json":new JsonOutput().toJson(root)]
    }
}
