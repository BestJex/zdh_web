package com.zyc.zdh.run;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.zyc.zdh.dao.QuartzJobMapper;
import com.zyc.zdh.dao.TaskLogInstanceMapper;
import com.zyc.zdh.dao.ZdhHaInfoMapper;
import com.zyc.zdh.entity.QuartzJobInfo;
import com.zyc.zdh.entity.TaskLogInstance;
import com.zyc.zdh.entity.ZdhHaInfo;
import com.zyc.zdh.job.EmailJob;
import com.zyc.zdh.job.JobCommon;
import com.zyc.zdh.job.JobModel;
import com.zyc.zdh.job.SnowflakeIdWorker;
import com.zyc.zdh.quartz.QuartzManager2;
import com.zyc.zdh.service.ZdhLogsService;
import com.zyc.zdh.shiro.RedisUtil;
import com.zyc.zdh.util.HttpUtil;
import com.zyc.zdh.util.SpringContext;
import org.apache.http.NameValuePair;
import org.apache.http.message.BasicNameValuePair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;


@Component
public class SystemCommandLineRunner implements CommandLineRunner {

    private Logger logger = LoggerFactory.getLogger(SystemCommandLineRunner.class);
    @Autowired
    QuartzManager2 quartzManager2;

    @Autowired
    QuartzJobMapper quartzJobMapper;

    @Autowired
    RedisUtil redisUtil;

    @Autowired
    Environment ev;

    @Override
    public void run(String... strings) throws Exception {
        runSnowflakeIdWorker();
        runRetryMQ();
        killJob();
        logger.info("初始化通知事件");
        EmailJob.notice_event();
        logger.info("初始化失败任务监控程序");
        //检测是否有email 任务 如果没有则添加
        QuartzJobInfo qj = new QuartzJobInfo();
        qj.setJob_type("EMAIL");
        List<QuartzJobInfo> quartzJobInfos = quartzJobMapper.select(qj);
        if (quartzJobInfos.size() > 0) {
            logger.info("已经存在[EMAIL]历史监控任务...");
        }else{
            logger.info("自动生成监控任务");
            String expr = ev.getProperty("email.schedule.interval");
            QuartzJobInfo quartzJobInfo = quartzManager2.createQuartzJobInfo("EMAIL", JobModel.REPEAT.getValue(), new Date(), new Date(), "", expr, "-1", "", "email");
            quartzJobInfo.setJob_id(SnowflakeIdWorker.getInstance().nextId() + "");
            quartzManager2.addQuartzJobInfo(quartzJobInfo);
            quartzManager2.addTaskToQuartz(quartzJobInfo);
        }

        //检测是否有email 任务 如果没有则添加
        QuartzJobInfo qj_retry = new QuartzJobInfo();
        qj_retry.setJob_type("RETRY");
        List<QuartzJobInfo> quartzJobInfos2 = quartzJobMapper.select(qj_retry);
        if (quartzJobInfos2.size() > 0) {
            logger.info("已经存在[RETRY]历史监控任务...");
        }else{
            logger.info("自动生成监控任务");
            String expr = ev.getProperty("retry.schedule.interval");
            QuartzJobInfo quartzJobInfo = quartzManager2.createQuartzJobInfo("RETRY", JobModel.REPEAT.getValue(), new Date(), new Date(), "", expr, "-1", "", "retry");
            quartzJobInfo.setJob_id(SnowflakeIdWorker.getInstance().nextId() + "");
            quartzManager2.addQuartzJobInfo(quartzJobInfo);
            quartzManager2.addTaskToQuartz(quartzJobInfo);
        }

    }


    public void runSnowflakeIdWorker() {
        logger.info("初始化分布式id生成器");
        //获取服务id
        String myid = ev.getProperty("myid", "0");
        String instance=ev.getProperty("instance","zdh_web");
        JobCommon.myid=myid;
        SnowflakeIdWorker.init(Integer.parseInt(myid), 0);
        JobCommon.web_application_id=instance+"_"+myid+":"+SnowflakeIdWorker.getInstance().nextId();
        new Thread(new Runnable() {
            @Override
            public void run() {
                while(true){
                    //此处设置10s 超时 ,quartz 故障检测时间为30s
                    redisUtil.set(instance+"_"+myid,JobCommon.web_application_id,10L, TimeUnit.SECONDS);
                    try {
                        //此处设置2s 每2秒向redis 设置一个当前服务,作为一个心跳检测使用
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        }).start();
    }

    public void runLogMQ(){
        logger.info("初始化日志");
        ZdhLogsService zdhLogsService = (ZdhLogsService) SpringContext.getBean("zdhLogsServiceImpl");
        JobCommon.logThread(zdhLogsService);
    }

    public void runRetryMQ(){
        logger.info("初始化重试队列事件");
        // JobCommon.retryThread();
    }

    public void killJob(){
        logger.info("初始化监控杀死任务");
        TaskLogInstanceMapper taskLogInstanceMapper = (TaskLogInstanceMapper) SpringContext.getBean("taskLogInstanceMapper");
        ZdhHaInfoMapper zdhHaInfoMapper = (ZdhHaInfoMapper) SpringContext.getBean("zdhHaInfoMapper");
        String myid = ev.getProperty("myid", "0");
        new Thread(new Runnable() {
            @Override
            public void run() {
                while(true){
                    try {
                        logger.debug("检查要杀死的任务..");
                        List<TaskLogInstance> tlis= taskLogInstanceMapper.selectThreadByStatus("kill");
                        for(TaskLogInstance tl : tlis){
                            if(tl.getThread_id()!=null && tl.getThread_id().startsWith(myid)){
                                Thread td=JobCommon.chm.get(tl.getThread_id());
                                if(td!=null){
                                    String msg="杀死线程:"+td.getName()+","+td.getId();
                                    logger.info(msg);
                                    JobCommon.insertLog(tl,"INFO",msg);
                                    try{
                                        td.interrupt();
                                        td.stop();
                                    }catch (Exception e){
                                        e.printStackTrace();
                                    }finally {
                                        JobCommon.chm.remove(tl.getThread_id());
                                        taskLogInstanceMapper.updateStatusById("killed",tl.getId());
                                    }
                                }else{
                                    String msg="调度部分已经执行完成,ETL部分正在执行提交到后端的任务进行杀死";
                                    logger.info(msg);
                                    JobCommon.insertLog(tl,"INFO",msg);
                                    String executor=tl.getExecutor();//数据采集机器id
                                    ZdhHaInfo zdhHaInfo=zdhHaInfoMapper.selectByPrimaryKey(executor);
                                    String jobGroup="jobGroup";
                                    if(zdhHaInfo!=null){
                                        String url="http://"+zdhHaInfo.getZdh_host()+":"+zdhHaInfo.getWeb_port()+"/api/v1/applications/"+zdhHaInfo.getApplication_id()+"/jobs";
                                        //获取杀死的任务名称
                                        System.out.println(url);
                                        List<NameValuePair> npl=new ArrayList<>();
                                        //npl.add(new BasicNameValuePair("status","running"));
                                        String restul=HttpUtil.getRequest(url,npl);
                                        JSONArray jsonArray= JSON.parseArray(restul);
                                        List<String> killJobs=new ArrayList<>();
                                        for(Object jo:jsonArray){
                                            JSONObject j=(JSONObject) jo;
                                            if(j.getString(jobGroup).startsWith(tl.getId())){
                                                killJobs.add(j.getString(jobGroup));
                                            }
                                        }

                                        JSONObject js=new JSONObject();
                                        js.put("task_logs_id",tl.getId());//写日志使用
                                        js.put("jobGroups",killJobs);
                                        js.put("job_id",tl.getJob_id());
                                        //发送杀死请求
                                        String kill_url="http://"+zdhHaInfo.getZdh_host()+":"+zdhHaInfo.getZdh_port()+"/api/v1/kill";
                                        HttpUtil.postJSON(kill_url,js.toJSONString());
                                        taskLogInstanceMapper.updateStatusById("killed",tl.getId());
                                    }

                                }
                            }
                        }
                        // List<QuartzJobInfo> quartzJobInfos = quartzJobMapper.select(qj);
                        Thread.sleep(1000*2);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        }).start();


    }
}
