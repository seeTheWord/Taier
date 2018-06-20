package com.dtstack.rdos.engine.enums;

public enum RequestStart {
	
    WEB(0),NODE(1);
    
    
    private int start;
    
    RequestStart(int start){
    	this.start = start;
    }
    
    public int getStart(){
    	return this.start;
    }
    
}