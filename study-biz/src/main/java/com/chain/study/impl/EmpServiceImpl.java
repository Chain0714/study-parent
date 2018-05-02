package com.chain.study.impl;

import com.chain.study.dao.EmpDao;
import com.chain.study.entity.Emp;
import com.chain.study.intf.EmpService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Created by 17032651 on 2017/6/16.
 */
@Service
public class EmpServiceImpl implements EmpService {

    @Autowired
    private EmpDao empDao;

    public List<Emp> getALlEmp() {
        return empDao.findAll();
    }
}
