package com.chain.study.dmo;

/**
 * Created by 17032651 on 2017/6/19.
 */
public class FunctionDmo {
    private int id;
    private String functionName;
    private String functionContent;
    private int inputNum;
    private String inputKey;
    private int outputNum;
    private String outputKey;

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getFunctionName() {
        return functionName;
    }

    public void setFunctionName(String functionName) {
        this.functionName = functionName;
    }

    public String getFunctionContent() {
        return functionContent;
    }

    public void setFunctionContent(String functionContent) {
        this.functionContent = functionContent;
    }

    public int getInputNum() {
        return inputNum;
    }

    public void setInputNum(int inputNum) {
        this.inputNum = inputNum;
    }

    public String getInputKey() {
        return inputKey;
    }

    public void setInputKey(String inputKey) {
        this.inputKey = inputKey;
    }

    public int getOutputNum() {
        return outputNum;
    }

    public void setOutputNum(int outputNum) {
        this.outputNum = outputNum;
    }

    public String getOutputKey() {
        return outputKey;
    }

    public void setOutputKey(String outputKey) {
        this.outputKey = outputKey;
    }

    public void calParamNum(){
        this.inputNum=(this.inputKey==null||this.inputKey=="")?0:this.inputKey.split(",").length;
        this.outputNum=(this.outputKey==null||this.outputKey=="")?0:this.outputKey.split(",").length;
        return;
    }
}
