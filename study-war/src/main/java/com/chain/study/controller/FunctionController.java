package com.chain.study.controller;

import com.chain.study.dmo.FunctionDmo;
import com.chain.study.dto.FunctionDto;
import com.chain.study.intf.FunctionService;
import com.chain.study.util.JSONUtil;
import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Created by 17032651 on 2017/6/19.
 */
@Controller
@RequestMapping("fun")
public class FunctionController {
    private Logger log = LoggerFactory.getLogger(FunctionController.class);

    @Autowired
    private FunctionService functionService;

    @RequestMapping("/findFun.do")
    public String find(Model model){
        List<FunctionDto> list = functionService.getAllFunction();
        model.addAttribute("funs", list);

        return "fun/fun_list";
    }

    @RequestMapping("addFun.do")
    public String AddFun(FunctionDmo dmo,@RequestParam("file") MultipartFile file){
        if(!file.isEmpty()){
            try {

                //这里将上传得到的文件保存至 d:\\temp\\file 目录
                FileUtils.copyInputStreamToFile(file.getInputStream(), new File("h:\\mydemo\\file\\",dmo.getFunctionName()+".groovy"));
            } catch (IOException e) {
                log.info("上传文件失败{}",e.getMessage());
            }
        }
        dmo.calParamNum();
        functionService.addFun(dmo);
        return "redirect:findFun.do";

    }

    @RequestMapping("getFun.do")
    public String getFun(String name,Model model){
        log.info("进入方法信息页面:{}",name);
        FunctionDto dto = functionService.getFunctionByName(name);
        List<FunctionDto> list = functionService.getAllFunction();
        model.addAttribute("fun",dto);
        model.addAttribute("funs", list);

        return "fun/fun_info";
    }

    @RequestMapping("invoke.do")
    @ResponseBody
    public String invoke(String name,String params){
        log.info("进入调用函数控制器...");
        Map<String,Object> map=JSONUtil.jsonToStringMap(params);
        Map<String,Object> resultMap = functionService.invoke(name, map);
        return JSONUtil.toJSONString(resultMap);
    }

    @RequestMapping("delFun.do")
    public String delFun(String name){
        functionService.delFunction(name);
        return "redirect:findFun.do";
    }

    @RequestMapping("chianInvoke.do")
    @ResponseBody
    public String chianInvoke(String functions,String params){
        List<String> names = Arrays.asList(functions.split(","));
        Map<String,Object> map=JSONUtil.jsonToStringMap(params);
        Map<String,Object> resultMap = functionService.invokeChain(names, map);
        if(null==resultMap){
            return "";
        }
        return JSONUtil.toJSONString(resultMap);
    }
}

