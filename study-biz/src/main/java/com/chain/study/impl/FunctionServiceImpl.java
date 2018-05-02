package com.chain.study.impl;

import com.chain.study.container.BaseGroovyInterface;
import com.chain.study.dao.FunctionDao;
import com.chain.study.dmo.FunctionDmo;
import com.chain.study.dto.FunctionDto;
import com.chain.study.intf.FunctionCacheService;
import com.chain.study.intf.FunctionService;
import groovy.lang.Binding;
import groovy.lang.GroovyClassLoader;
import groovy.lang.GroovyObject;
import groovy.lang.GroovyShell;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Created by 17032651 on 2017/6/19.
 */
@Service
public class FunctionServiceImpl implements FunctionService {

    private Logger log = LoggerFactory.getLogger(FunctionServiceImpl.class);

    @Autowired
    private FunctionCacheService functionCacheService;

    @Autowired
    private FunctionDao functionDao;

    public int addFun(FunctionDmo dmo) {
        return functionDao.addFunction(dmo);
    }

    public FunctionDto getFunctionByName(String name) {
        FunctionDmo dmo = functionCacheService.getFunctionCache(name);
        if(null==dmo){
            log.info("函数不存在{}",name);
            return null;
        }
        FunctionDto dto = new FunctionDto();
        dto.convertFromDmo(dmo);
        return dto;
    }

    public int delFunction(String name) {
        return functionDao.delFunction(name);
    }

    public Map<String, Object> invoke(String name, Map<String,Object> params) {
        log.info("开始执行函数，name:{},params:{}",name,params.toString());
        File file = new File("h:\\mydemo\\file\\"+name+".groovy");
        if (file.exists()) {
            log.info("执行函数文件");
            GroovyClassLoader loader= new GroovyClassLoader();
            try {
                Class groovyClass = loader.parseClass(file);
                BaseGroovyInterface instance =(BaseGroovyInterface) groovyClass.newInstance();
                return instance.run(params);
            } catch (Exception e) {
                log.info("加载函数文件失败{}",e.getMessage());
            }
            return null;
        }
        FunctionDto dto = getFunctionByName(name);
        Binding binding = new Binding();
        for(String param : dto.getInputKeys()){
            binding.setVariable(param,params.get(param));
        }
        log.info("groovy脚本:{}",dto.getContent());
        GroovyShell groovyShell = new GroovyShell(binding);
        try{
            Object result =groovyShell.evaluate(dto.getContent());
            log.info("函数执行成功:{}",result.toString());
            return (Map)result;
        }catch(Exception e){
            log.info("函数执行失败:{}",e.getMessage());
            return null;
        }
    }

    public Map<String, Object> invokeChain(List<String> functions, Map<String, Object> params) {
        if(!checkFunctionChain(functions)){
            log.info("函数链不合法");
            return null;
        }
        Map<String, Object> result=params;
        for(int i=0;i<functions.size();i++){
            result=invoke(functions.get(i),result);
        }
        return result;
    }

    private boolean checkFunctionChain(List<String> functions) {
        if(functions.size()<2){
            return false;
        }
        for(int i=0;i<functions.size()-1;i++){
            FunctionDmo curr=functionCacheService.getFunctionCache(functions.get(i));
            FunctionDmo next=functionCacheService.getFunctionCache(functions.get(i+1));
            if(null==curr||null==next){
                return false;
            }
            if(!curr.getOutputKey().equals(next.getInputKey())){
                return false;
            }
        }
        return true;
    }

    public List<FunctionDto> getAllFunction() {
        List<FunctionDmo> dmos= functionDao.getAllFunction();
        List<FunctionDto> dtos= new ArrayList<FunctionDto>();
        for(FunctionDmo dmo:dmos){
            FunctionDto dto = new FunctionDto();
            dto.convertFromDmo(dmo);
            dtos.add(dto);
        }
        return dtos;
    }
}
