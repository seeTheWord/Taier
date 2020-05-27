package com.dtstack.engine.master.jobdealer;

import com.alibaba.fastjson.JSONObject;
import com.dtstack.engine.api.domain.ScheduleJob;
import com.dtstack.engine.api.domain.ScheduleTaskShade;
import com.dtstack.engine.common.JobClient;
import com.dtstack.engine.common.enums.EJobCacheStage;
import com.dtstack.engine.common.pojo.JobResult;
import com.dtstack.engine.common.pojo.StoppedJob;
import com.dtstack.engine.common.CustomThreadFactory;
import com.dtstack.engine.common.enums.RdosTaskStatus;
import com.dtstack.engine.common.pojo.ParamAction;
import com.dtstack.engine.common.queue.DelayBlockingQueue;
import com.dtstack.engine.common.util.PublicUtil;
import com.dtstack.engine.dao.EngineJobCacheDao;
import com.dtstack.engine.dao.ScheduleJobDao;
import com.dtstack.engine.dao.EngineJobStopRecordDao;
import com.dtstack.engine.api.domain.EngineJobCache;
import com.dtstack.engine.api.domain.EngineJobStopRecord;
import com.dtstack.engine.common.enums.StoppedStatus;
import com.dtstack.engine.master.akka.WorkerOperator;
import com.dtstack.engine.master.env.EnvironmentContext;
import com.dtstack.engine.master.cache.ShardCache;
import com.dtstack.engine.master.impl.ScheduleTaskShadeService;
import com.dtstack.schedule.common.enums.EScheduleJobType;
import com.dtstack.schedule.common.enums.ScheduleEngineType;
import com.google.common.collect.Lists;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * company: www.dtstack.com
 * author: toutian
 * create: 2020/5/26
 */
@Component
public class JobStopDealer implements InitializingBean {

    private static final Logger logger = LoggerFactory.getLogger(JobStopDealer.class);

    @Autowired
    private ShardCache shardCache;

    private DelayBlockingQueue<StoppedJob<JobElement>> stopJobQueue = new DelayBlockingQueue<StoppedJob<JobElement>>(1000);

    @Autowired
    private EngineJobCacheDao engineJobCacheDao;

    @Autowired
    private EngineJobStopRecordDao engineJobStopRecordDao;

    @Autowired
    private ScheduleJobDao scheduleJobDao;

    @Autowired
    private EnvironmentContext environmentContext;

    @Autowired
    private WorkerOperator workerOperator;

    @Autowired
    private ScheduleTaskShadeService batchTaskShadeService;


    private static final int WAIT_INTERVAL = 1000;
    private static final int OPERATOR_EXPIRED_INTERVAL = 60000;

    private int jobStoppedRetry;
    private long jobStoppedDelay;

    private ExecutorService simpleEs = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(), new CustomThreadFactory(this.getClass().getSimpleName()));

    private ScheduledExecutorService scheduledService = new ScheduledThreadPoolExecutor(1, new CustomThreadFactory(this.getClass().getSimpleName()));

    private StopProcessor stopProcessor = new StopProcessor();
    private AcquireStopJob acquireStopJob = new AcquireStopJob();

    private static final List<Integer> SPECIAL_TASK_TYPES = Lists.newArrayList(EScheduleJobType.WORK_FLOW.getVal(), EScheduleJobType.ALGORITHM_LAB.getVal());

    /**
     * 提交jobs判断是否已经提交，若没有提交入DAO进行后续操作
     * @param jobs 传入的List参数
     */
    public int addStopJobs(List<ScheduleJob> jobs,  Long dtuicTenantId, Integer appType) {
        if (CollectionUtils.isEmpty(jobs)) {
            return 0;
        }
        int stopCount = 0;
        List<ScheduleJob> needSendStopJobs = new ArrayList<>(jobs.size());
        List<Long> unSubmitJob = new ArrayList<>(jobs.size());
        for (ScheduleJob job : jobs) {
            //除了未提交的任务--其他都是发消息到engine端停止
            if (checkJobCanStop(job.getStatus())) {
                stopCount++;
                if (RdosTaskStatus.UNSUBMIT.getStatus().equals(job.getStatus()) || SPECIAL_TASK_TYPES.contains(job.getTaskType())) {
                    unSubmitJob.add(job.getId());
                }
                //engineto同步状态可能会覆盖 所以都需要提交engine 更新
                needSendStopJobs.add(job);
            }
        }

        List<Long> taskIds = jobs.parallelStream().map(ScheduleJob::getTaskId).collect(Collectors.toList());

        // 停止已提交的
        if (CollectionUtils.isNotEmpty(needSendStopJobs)) {
            //转换ScheduleJob类型为EngineJobStopRecord类型
            Map<Long, List<ScheduleTaskShade>> taskShades =
                    batchTaskShadeService.getTaskByIds(taskIds, appType)
                            .stream()
                            .collect(Collectors.groupingBy(ScheduleTaskShade::getTaskId));


            for (ScheduleJob job : jobs) {
                Map<String, Object> param = new HashMap<>();
                List<ScheduleTaskShade> shades = taskShades.get(job.getTaskId());

                if (CollectionUtils.isNotEmpty(shades)) {
                    ScheduleTaskShade batchTask = shades.get(0);
                    param.put("engineType", ScheduleEngineType.getEngineName(batchTask.getEngineType()));
                    param.put("taskId", job.getJobId());
                    param.put("computeType", batchTask.getComputeType());
                    param.put("taskType", batchTask.getTaskType());
                    param.put("tenantId", dtuicTenantId);
                    if (batchTask.getTaskType().equals(EScheduleJobType.DEEP_LEARNING.getVal())) {
                        param.put("engineType", ScheduleEngineType.Learning.getEngineName());
                        param.put("taskType", EScheduleJobType.SPARK_PYTHON.getVal());
                    } else if (batchTask.getTaskType().equals(EScheduleJobType.PYTHON.getVal()) || batchTask.getTaskType().equals(EScheduleJobType.SHELL.getVal())) {
                        param.put("engineType", ScheduleEngineType.DtScript.getEngineName());
                        param.put("taskType", EScheduleJobType.SPARK_PYTHON.getVal());
                    }
                    EngineJobStopRecord jobStopRecord = EngineJobStopRecord.toEntity(param);
                    engineJobStopRecordDao.insert(jobStopRecord);
                }
            }
        }
        //更新未提交任务状态
        if (CollectionUtils.isNotEmpty(unSubmitJob)) {
            scheduleJobDao.updateJobStatusByIds(RdosTaskStatus.CANCELED.getStatus(), unSubmitJob);
        }

        return stopCount;

    }

    private boolean checkJobCanStop(Integer status) {
        if (status == null) {
            return true;
        }

        return RdosTaskStatus.getCanStopStatus().contains(status);
    }




    @Override
    public void afterPropertiesSet() {
        logger.info("Initializing " + this.getClass().getName());

        jobStoppedRetry = environmentContext.getJobStoppedRetry();
        jobStoppedDelay = environmentContext.getJobStoppedDelay();

        if (simpleEs.isShutdown()) {
            simpleEs = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(), new CustomThreadFactory(this.getClass().getSimpleName() + "_StopProcessor"));
            stopProcessor.reStart();
        }

        simpleEs.submit(stopProcessor);

        scheduledService.scheduleAtFixedRate(
                acquireStopJob,
                WAIT_INTERVAL,
                WAIT_INTERVAL,
                TimeUnit.MILLISECONDS);
    }

    public void stop() {
        stopProcessor.stop();
        simpleEs.shutdownNow();
    }

    public boolean tryPutStopJobQueue(ParamAction paramAction) {
        JobElement jobElement = new JobElement(paramAction.getTaskId(), paramAction.getStopJobId());
        return stopJobQueue.tryPut(new StoppedJob<JobElement>(jobElement, jobStoppedRetry, jobStoppedDelay));
    }

    private class AcquireStopJob implements Runnable {
        @Override
        public void run() {
            long tmpStartId = 0L;
            Timestamp operatorExpired = new Timestamp(System.currentTimeMillis() + OPERATOR_EXPIRED_INTERVAL);
            Timestamp lessThanOperatorExpired = new Timestamp(System.currentTimeMillis());
            while (true) {
                try {
                    //根据条件判断是否有数据存在
                    List<EngineJobStopRecord> jobStopRecords = engineJobStopRecordDao.listStopJob(tmpStartId, lessThanOperatorExpired);
                    if (jobStopRecords.isEmpty()) {
                        break;
                    }
                    //使用乐观锁防止多节点重复停止任务
                    Iterator<EngineJobStopRecord> it = jobStopRecords.iterator();
                    while (it.hasNext()) {
                        EngineJobStopRecord jobStopRecord = it.next();
                        tmpStartId = jobStopRecord.getId();
                        //已经被修改过version的任务代表其他节点正在处理，可以忽略
                        Integer update = engineJobStopRecordDao.updateOperatorExpiredVersion(jobStopRecord.getId(), operatorExpired, jobStopRecord.getVersion());
                        if (update != 1) {
                            it.remove();
                        }
                    }
                    //经乐观锁判断，经过remove后所剩下的数据
                    if (jobStopRecords.isEmpty()) {
                        break;
                    }
                    List<String> jobIds = jobStopRecords.stream().map(EngineJobStopRecord::getTaskId).collect(Collectors.toList());
                    List<EngineJobCache> jobCaches = engineJobCacheDao.getByJobIds(jobIds);

                    //为了下面兼容异常状态的任务停止
                    Map<String, EngineJobCache> jobCacheMap = new HashMap<>(jobCaches.size());
                    for (EngineJobCache jobCache : jobCaches) {
                        jobCacheMap.put(jobCache.getJobId(), jobCache);
                    }

                    for (EngineJobStopRecord jobStopRecord : jobStopRecords) {
                        EngineJobCache jobCache = jobCacheMap.get(jobStopRecord.getTaskId());
                        if (jobCache != null) {
                            //停止任务的时效性，发起停止操作要比任务存入jobCache表的时间要迟
                            if (jobCache.getGmtCreate().after(jobStopRecord.getGmtCreate())) {
                                engineJobStopRecordDao.delete(jobStopRecord.getId());
                                continue;
                            }

                            JobElement jobElement = new JobElement(jobCache.getJobId(), jobStopRecord.getId());
                            stopJobQueue.put(new StoppedJob<JobElement>(jobElement, jobStoppedRetry, jobStoppedDelay));
                        } else {
                            //jobcache表没有记录，可能任务已经停止。在update表时增加where条件不等于stopped
                            scheduleJobDao.updateTaskStatusNotStopped(jobStopRecord.getTaskId(), RdosTaskStatus.CANCELED.getStatus(), RdosTaskStatus.getStoppedStatus());
                            logger.info("[Unnormal Job] jobId:{} update job status:{}, job is finished.", jobStopRecord.getTaskId(), RdosTaskStatus.CANCELED.getStatus());
                            shardCache.updateLocalMemTaskStatus(jobStopRecord.getTaskId(), RdosTaskStatus.CANCELED.getStatus());
                            engineJobStopRecordDao.delete(jobStopRecord.getId());
                        }
                    }

                    Thread.sleep(500);
                } catch (Throwable e) {
                    logger.error("when acquire stop jobs happens error:{}", e);
                }
            }
        }
    }

    private class StopProcessor implements Runnable {

        private boolean run = true;

        @Override
        public void run() {

            logger.info("job stop process thread is start...");

            while (run) {
                try {
                    StoppedJob<JobElement> stoppedJob = stopJobQueue.take();
                    if (!checkExpired(stoppedJob.getJob())){
                        StoppedStatus stoppedStatus = JobStopDealer.this.stopJob(stoppedJob.getJob());
                        switch (stoppedStatus) {
                            case STOPPED:
                            case MISSED:
                                engineJobStopRecordDao.delete(stoppedJob.getJob().stopJobId);
                                break;
                            case STOPPING:
                            case RETRY:
                                if (stoppedJob.isRetry()) {
                                    if (StoppedStatus.STOPPING == stoppedStatus) {
                                        stoppedJob.resetDelay(jobStoppedDelay * 20);
                                    } else if (StoppedStatus.RETRY == stoppedStatus) {
                                        stoppedJob.resetDelay(jobStoppedDelay);
                                    }
                                    stoppedJob.incrCount();
                                    stopJobQueue.put(stoppedJob);
                                } else {
                                    logger.warn("jobId:{} retry limited!", stoppedJob.getJob().jobId);
                                }
                            default:
                        }
                    }
                    engineJobStopRecordDao.delete(stoppedJob.getJob().stopJobId);
                } catch (Exception e) {
                    logger.error("", e);
                }
            }

            logger.info("job stop process thread is shutdown...");

        }

        public void stop() {
            this.run = false;
        }

        public void reStart() {
            this.run = true;
        }
    }

    private StoppedStatus stopJob(JobElement jobElement) throws Exception {
        EngineJobCache jobCache = engineJobCacheDao.getOne(jobElement.jobId);
        ScheduleJob scheduleJob = scheduleJobDao.getRdosJobByJobId(jobElement.jobId);
        if (jobCache == null) {
            if (scheduleJob != null && RdosTaskStatus.isStopped(scheduleJob.getStatus())) {
                logger.info("jobId:{} stopped success, task status is STOPPED.", jobElement.jobId);
                return StoppedStatus.STOPPED;
            }
            logger.info("jobId:{} cache is missed, stop interrupt.", jobElement.jobId);
            return StoppedStatus.MISSED;
        } else if (EJobCacheStage.unSubmitted().contains(jobCache.getStage())) {
            removeMemStatusAndJobCache(jobCache.getJobId());
            logger.info("jobId:{} stopped success, task status is STOPPED.", jobElement.jobId);
            return StoppedStatus.STOPPED;
        } else {
            if (scheduleJob == null) {
                logger.info("jobId:{} cache is missed, stop interrupt.", jobElement.jobId);
                return StoppedStatus.MISSED;
            }
            ParamAction paramAction = PublicUtil.jsonStrToObject(jobCache.getJobInfo(), ParamAction.class);
            paramAction.setEngineTaskId(scheduleJob.getEngineJobId());
            paramAction.setApplicationId(scheduleJob.getApplicationId());
            JobClient jobClient = new JobClient(paramAction);

            if (StringUtils.isNotBlank(scheduleJob.getEngineJobId()) && !jobClient.getEngineTaskId().equals(scheduleJob.getEngineJobId())) {
                logger.info("jobId:{} stopped success, because of [difference engineJobId].", paramAction.getTaskId());
                return StoppedStatus.STOPPED;
            }

            JobResult jobResult = workerOperator.stopJob(jobClient);
            if (jobResult.getCheckRetry()) {
                logger.info("jobId:{} is retry.", paramAction.getTaskId());
                return StoppedStatus.RETRY;
            } else {
                logger.info("jobId:{} is stopping.", paramAction.getTaskId());
                return StoppedStatus.STOPPING;
            }
        }

    }

    private void removeMemStatusAndJobCache(String jobId) {
        shardCache.removeIfPresent(jobId);
        engineJobCacheDao.delete(jobId);
        //修改任务状态
        scheduleJobDao.updateJobStatusAndExecTime(jobId, RdosTaskStatus.CANCELED.getStatus());
        logger.info("jobId:{} update job status:{}, job is finished.", jobId, RdosTaskStatus.CANCELED.getStatus());
    }

    private boolean checkExpired(JobElement jobElement){
        EngineJobCache jobCache = engineJobCacheDao.getOne(jobElement.jobId);
        Timestamp getGmtCreate = engineJobStopRecordDao.getJobCreateTimeById(jobElement.stopJobId);
        if (jobCache != null && getGmtCreate != null){
            return jobCache.getGmtCreate().after(getGmtCreate);
        } else {
            return true;
        }
    }

    private class JobElement {

        public String jobId;
        public long stopJobId;

        public JobElement(String jobId, long stopJobId) {
            this.jobId = jobId;
            this.stopJobId = stopJobId;
        }
    }
}
