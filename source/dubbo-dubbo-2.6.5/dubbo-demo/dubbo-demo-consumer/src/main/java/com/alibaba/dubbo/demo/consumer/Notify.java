package com.alibaba.dubbo.demo.consumer;

public class Notify {
    public void oninvoke(String msg){
        System.out.println("oninvoke:" + msg);
    }
    public void onreturn(String msg) {
        System.out.println("onreturn:" + msg);
    }
    public void onthrow(Throwable e) {
        System.out.println("onthrow:" + e);
    }
}
