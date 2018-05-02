package com.chain.study.entity;

import java.util.List;

public class Condition {
	private Integer depNo;
	private Double salary;
	private List<Integer> ids;
	
	public Integer getDepNo() {
		return depNo;
	}
	public void setDepNo(Integer depNo) {
		this.depNo = depNo;
	}
	public Double getSalary() {
		return salary;
	}
	public void setSalary(Double salary) {
		this.salary = salary;
	}
	public List<Integer> getIds() {
		return ids;
	}
	public void setIds(List<Integer> ids) {
		this.ids = ids;
	}
	
}
