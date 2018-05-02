package com.chain.study.dao;

import com.chain.study.annotation.MyBatisRepository;
import com.chain.study.entity.Condition;
import com.chain.study.entity.Emp;

import java.util.List;

/**
 * DAO组件
 *
 * @author xusha
 */
@MyBatisRepository
public interface EmpDao {
    List<Emp> findAll();

    List<Emp> findByEmp(Condition cond);

    List<Emp> findBySalary(Condition cond);

    List<Emp> findByDepNoAndSalary(Condition cond);

    void updateEmp(Emp emp);

    List<Emp> findByDepNoAndSalary2(Condition cond);

    void updateEmp2(Emp emp);

    List<Emp> findByIds(Condition cond);
}
