package com.chain.study.dto;

import com.chain.study.dmo.FunctionDmo;

import java.io.Serializable;
import java.util.Arrays;
import java.util.List;

/**
 * Created by 17032651 on 2017/6/19.
 */
public class FunctionDto implements Serializable{
    private static final long serialVersionUID = 7360837980644945222L;

    private int id;
    private String name;
    private String content;
    private int inputNum;
    private List<String> inputKeys;
    private int outputNum;
    private List<String> outputKeys;

    public void convertFromDmo(FunctionDmo dmo){
        this.id = dmo.getId();
        this.name = dmo.getFunctionName();
        this.content = dmo.getFunctionContent();
        this.inputNum = dmo.getInputNum();
        this.inputKeys = Arrays.asList(dmo.getInputKey().split(","));
        this.outputNum = dmo.getOutputNum();
        this.outputKeys = Arrays.asList(dmo.getOutputKey().split(","));
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public int getInputNum() {
        return inputNum;
    }

    public void setInputNum(int inputNum) {
        this.inputNum = inputNum;
    }

    public List<String> getInputKeys() {
        return inputKeys;
    }

    public void setInputKeys(List<String> inputKeys) {
        this.inputKeys = inputKeys;
    }

    public int getOutputNum() {
        return outputNum;
    }

    public void setOutputNum(int outputNum) {
        this.outputNum = outputNum;
    }

    public List<String> getOutputKeys() {
        return outputKeys;
    }

    public void setOutputKeys(List<String> outputKeys) {
        this.outputKeys = outputKeys;
    }
}
