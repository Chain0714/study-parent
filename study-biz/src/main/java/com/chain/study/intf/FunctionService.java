package com.chain.study.intf;

import com.chain.study.dmo.FunctionDmo;
import com.chain.study.dto.FunctionDto;

import java.util.List;
import java.util.Map;

/**
 * Created by 17032651 on 2017/6/19.
 */
public interface FunctionService {
    Map<String,Object> invoke(String name,Map<String,Object> params);

    Map<String,Object> invokeChain(List<String> functions,Map<String,Object> params);

    List<FunctionDto> getAllFunction();

    int addFun(FunctionDmo dmo);

    FunctionDto getFunctionByName(String name);

    int delFunction(String name);


}
