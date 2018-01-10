package com.dtstack.rdos.engine.db.dataobject;

/**
 * Reason:
 * Date: 2017/11/6
 * Company: www.dtstack.com
 * @ahthor xuchao
 */

public class RdosEngineJobCache extends DataObject{

    private String jobId;

    private String jobInfo;

    private String engineType;

    private Integer computeType;

    private int stage;

    public String getJobId() {
        return jobId;
    }

    public void setJobId(String jobId) {
        this.jobId = jobId;
    }

    public String getJobInfo() {
        return jobInfo;
    }

    public void setJobInfo(String jobInfo) {
        this.jobInfo = jobInfo;
    }

    public String getEngineType() {
        return engineType;
    }

    public void setEngineType(String engineType) {
        this.engineType = engineType;
    }

    public Integer getComputeType() {
        return computeType;
    }

    public void setComputeType(Integer computeType) {
        this.computeType = computeType;
    }

    public int getStage() {
        return stage;
    }

    public void setStage(int stage) {
        this.stage = stage;
    }
}
