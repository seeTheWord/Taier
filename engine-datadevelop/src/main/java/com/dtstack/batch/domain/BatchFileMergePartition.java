/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.dtstack.batch.domain;

import com.dtstack.engine.domain.TenantEntity;

import java.sql.Timestamp;

/**
 * <p>小文件合并分区记录实体类
 *
 * @author ：wangchuan
 * date：Created in 11:31 上午 2020/12/14
 * company: www.dtstack.com
 */
public class BatchFileMergePartition extends TenantEntity {

    /**
     * 合并记录id
     */
    private Long recordId;

    /**
     * 是否是分区表，1为true，0为false
     */
    private Integer status;

    /**
     * 合并开始时间
     */
    private Timestamp startTime;

    /**
     * 合并结束时间
     */
    private Timestamp endTime;

    /**
     * 错误记录
     */
    private String errorMsg;

    /**
     * 分区名
     */
    private String partitionName;

    /**
     * 分区备份路径
     */
    private String copyLocation;

    /**
     * 合并前占用存储
     */
    private String storageBefore;

    /**
     * 合并后占用存储
     */
    private String storageAfter;

    /**
     * 合并前文件数量
     */
    private Long fileCountBefore;

    /**
     * 合并后文件数量
     */
    private Long fileCountAfter;

    public Long getRecordId() {
        return recordId;
    }

    public void setRecordId(Long recordId) {
        this.recordId = recordId;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Timestamp getStartTime() {
        return startTime;
    }

    public void setStartTime(Timestamp startTime) {
        this.startTime = startTime;
    }

    public Timestamp getEndTime() {
        return endTime;
    }

    public void setEndTime(Timestamp endTime) {
        this.endTime = endTime;
    }

    public String getErrorMsg() {
        return errorMsg;
    }

    public void setErrorMsg(String errorMsg) {
        this.errorMsg = errorMsg;
    }

    public String getPartitionName() {
        return partitionName;
    }

    public void setPartitionName(String partitionName) {
        this.partitionName = partitionName;
    }

    public String getCopyLocation() {
        return copyLocation;
    }

    public void setCopyLocation(String copyLocation) {
        this.copyLocation = copyLocation;
    }

    public String getStorageBefore() {
        return storageBefore;
    }

    public void setStorageBefore(String storageBefore) {
        this.storageBefore = storageBefore;
    }

    public String getStorageAfter() {
        return storageAfter;
    }

    public void setStorageAfter(String storageAfter) {
        this.storageAfter = storageAfter;
    }

    public Long getFileCountBefore() {
        return fileCountBefore;
    }

    public void setFileCountBefore(Long fileCountBefore) {
        this.fileCountBefore = fileCountBefore;
    }

    public Long getFileCountAfter() {
        return fileCountAfter;
    }

    public void setFileCountAfter(Long fileCountAfter) {
        this.fileCountAfter = fileCountAfter;
    }

}