package com.chain.study.test;

import com.chain.study.dao.FunctionDao;
import com.chain.study.dmo.FunctionDmo;
import com.chain.study.dto.FunctionDto;
import com.chain.study.intf.FunctionCacheService;
import com.chain.study.intf.FunctionService;
import junit.framework.TestCase;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Created by 17032651 on 2017/6/19.
 */
public class Test extends TestCase{

    public void testFunctionDao(){
        ApplicationContext ctx = new ClassPathXmlApplicationContext("config/application-context-test.xml");
        FunctionDao dao = (FunctionDao) ctx.getBean("functionDao");
        FunctionDmo dmo = new FunctionDmo();
        dmo.setFunctionName("aa");
        dmo.setFunctionContent("lala");
        dmo.setInputKey("go");
        dmo.setInputNum(4);
        dmo.setOutputNum(3);
        dmo.setOutputKey("kdkd");
        dao.addFunction(dmo);

        System.out.println(dao.getAllFunction());
    }

    public void testFunctionService(){
        ApplicationContext ctx = new ClassPathXmlApplicationContext("config/application-context-test.xml");
        FunctionService service = (FunctionService) ctx.getBean("functionServiceImpl");
        Map<String,Object> params = new HashMap<String,Object>();
        params.put("x",10);
        params.put("y",8);
        Map<String,Object> result = service.invoke("sum",params);
        System.out.println(result);
    }

    public void testChain(){
        ApplicationContext ctx = new ClassPathXmlApplicationContext("config/application-context-test.xml");
        FunctionService service = (FunctionService) ctx.getBean("functionServiceImpl");
        Map<String,Object> params = new HashMap<String,Object>();
        params.put("x",10);
        params.put("y",8);
        List<String> names = new ArrayList<String>();
        names.add("fun1");
        names.add("fun2");
        names.add("fun3");
        System.out.println(service.invokeChain(names,params));
    }

    public void testCache(){
        ApplicationContext ctx = new ClassPathXmlApplicationContext("config/application-context-test.xml");
        FunctionCacheService service = (FunctionCacheService) ctx.getBean("functionCacheServiceImpl");
        FunctionDmo dmo = service.getFunctionCache("ccc");
        assert null==dmo;
    }

}
