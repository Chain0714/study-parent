package com.chain.study.intf;


import com.chain.study.entity.Emp;

import java.util.List;

/**
 * Created by 17032651 on 2017/6/16.
 */
public interface EmpService {
    /**
     * 获取所有员工
     * @return
     */
    List<Emp> getALlEmp();
}
