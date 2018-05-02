package com.chain.study.dao;

import com.chain.study.annotation.MyBatisRepository;
import com.chain.study.dmo.FunctionDmo;

import java.util.List;

/**
 * Created by 17032651 on 2017/6/19.
 */
@MyBatisRepository
public interface FunctionDao {
    FunctionDmo getFunctionByName(String name);


    int addFunction(FunctionDmo dmo);

    List<FunctionDmo> getAllFunction();

    int delFunction(String name);
}
