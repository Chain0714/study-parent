package com.chain.study.controller;

import javax.annotation.Resource;

import com.chain.study.entity.Emp;
import com.chain.study.intf.EmpService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;

@Controller
@RequestMapping("emp")
public class EmpController {

//	private Logger log = LoggerFactory.getLogger(EmpController.class);

	@Autowired
	private EmpService empServiceImpl;
	
	@RequestMapping("/findEmp.do")
	public String find(Model model){
		List<Emp> list = empServiceImpl.getALlEmp();
		model.addAttribute("emps", list);

		return "emp/emp_list";
	}
}
