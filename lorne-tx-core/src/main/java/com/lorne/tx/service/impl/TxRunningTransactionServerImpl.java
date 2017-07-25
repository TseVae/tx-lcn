package com.lorne.tx.service.impl;

import com.lorne.core.framework.exception.ServiceException;
import com.lorne.core.framework.utils.KidUtils;
import com.lorne.core.framework.utils.config.ConfigUtils;
import com.lorne.core.framework.utils.http.HttpUtils;
import com.lorne.core.framework.utils.task.ConditionUtils;
import com.lorne.core.framework.utils.task.IBack;
import com.lorne.core.framework.utils.task.Task;
import com.lorne.tx.bean.TxTransactionInfo;
import com.lorne.tx.bean.TxTransactionLocal;
import com.lorne.tx.compensate.service.CompensateService;
import com.lorne.tx.mq.handler.TransactionHandler;
import com.lorne.tx.mq.model.TxGroup;
import com.lorne.tx.mq.service.MQTxManagerService;
import com.lorne.tx.mq.service.NettyService;
import com.lorne.tx.service.TransactionServer;
import com.lorne.tx.service.model.ServiceThreadModel;
import com.lorne.tx.utils.ThreadPoolSizeHelper;
import org.aspectj.lang.ProceedingJoinPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import java.util.concurrent.*;

/**
 * 分布式事务启动开始时的业务处理
 * Created by lorne on 2017/6/8.
 */
@Service(value = "txRunningTransactionServer")
public class TxRunningTransactionServerImpl implements TransactionServer {


    private Logger logger = LoggerFactory.getLogger(TxRunningTransactionServerImpl.class);


    @Autowired
    private PlatformTransactionManager txManager;

    @Autowired
    private MQTxManagerService txManagerService;


    @Autowired
    private NettyService nettyService;

    @Autowired
    private CompensateService compensateService;


    private String url;


    private ScheduledExecutorService executorService = Executors.newScheduledThreadPool(ThreadPoolSizeHelper.getInstance().getInThreadSize());



    private Executor threadPool  = Executors.newFixedThreadPool(ThreadPoolSizeHelper.getInstance().getStartSize());


    public TxRunningTransactionServerImpl() {
        url = ConfigUtils.getString("tx.properties", "url");
    }



    public Object secondExecute(final TxTransactionInfo info,Task groupTask,final ProceedingJoinPoint point) throws Throwable {
        logger.info("tx-second-running-start");

        //需要添加事务组，修改事务状态
        String key  = (String) groupTask.getBack().doing();
        Task waitTask  = ConditionUtils.getInstance().getTask(key);

        final String groupId = groupTask.getKey();


        Object obj =  waitTask.execute(new IBack() {
            @Override
            public Object doing(Object... objs) throws Throwable {


                String kid = KidUtils.generateShortUuid();

                DefaultTransactionDefinition def = new DefaultTransactionDefinition();
                def.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
                TransactionStatus status = txManager.getTransaction(def);


                try {


                    String compensateId = compensateService.saveTransactionInfo(info.getInvocation(), groupId, kid);

                    Object obj = point.proceed();

                    TxGroup txGroup = txManagerService.addTransactionGroup(groupId, kid,true);

                    //获取不到模块信息重新连接，本次事务异常返回数据.
                    if (txGroup == null) {
                        if (!TransactionHandler.net_state) {
                            nettyService.restart();
                        }
                        txManager.rollback(status);
                        throw new ServiceException("添加事务组异常.");
                    }else{
                        txManager.commit(status);
                    }

                    compensateService.deleteTransactionInfo(compensateId);

                    return obj;
                }catch (Throwable e){
                    //失败会通知到tx
                    txManager.rollback(status);
                    logger.info("tx-second-running-end");
                    throw e;
                }
            }
        });

        if(obj instanceof  Throwable){
            throw (Throwable) obj;
        }

        logger.info("tx-second-running-end");
        return obj;
    }

    @Override
    public Object execute(final ProceedingJoinPoint point, final TxTransactionInfo info) throws Throwable {


        final String txGroupId = info.getTxGroupId();
        Task groupTask =  ConditionUtils.getInstance().getTask(txGroupId);

        //当同一个事务下的业务进入切面时，合并业务执行。
        if(groupTask!=null&&!groupTask.isNotify()){
            return secondExecute(info,groupTask,point);
        }

        //分布式事务开始执行
        logger.info("tx-running-start");
        final String taskId = KidUtils.generateShortUuid();
        final Task task = ConditionUtils.getInstance().createTask(taskId);

        threadPool.execute(new Runnable() {
            @Override
            public void run() {

                TxTransactionLocal txTransactionLocal = new TxTransactionLocal();
                txTransactionLocal.setGroupId(txGroupId);
                TxTransactionLocal.setCurrent(txTransactionLocal);


                ServiceThreadModel model = serviceInThread(info, txGroupId, task, point);
                if (model == null) {
                    return;
                }

                Task groupTask = ConditionUtils.getInstance().createTask(txGroupId);
                final String waitTaskKey = model.getWaitTask().getKey();
                groupTask.setBack(new IBack() {
                    @Override
                    public Object doing(Object... objs) throws Throwable {
                        return waitTaskKey;
                    }
                });

                logger.info("taskId-id-tx-running:" + waitTaskKey);
                serviceWait( task, model);

                groupTask.remove();
            }
        });

        task.awaitTask();

        logger.info("tx-running-end");
        //分布式事务执行完毕
        try {
            return task.getBack().doing();
        } finally {
            task.remove();
        }
    }


    public ServiceThreadModel serviceInThread(TxTransactionInfo info, String _groupId, Task task, ProceedingJoinPoint point) {

        String kid = KidUtils.generateShortUuid();
//        TxGroup txGroup = txManagerService.addTransactionGroup(_groupId, kid,false);
//
//        //获取不到模块信息重新连接，本次事务异常返回数据.
//        if (txGroup == null) {
//            task.setBack(new IBack() {
//                @Override
//                public Object doing(Object... objects) throws Throwable {
//                    throw new ServiceException("添加事务组异常.");
//                }
//            });
//            task.signalTask();
//            if (!TransactionHandler.net_state) {
//                nettyService.restart();
//            }
//            return null;
//        }

        //一直获取连接导致数据库连接到最大值️
        DefaultTransactionDefinition def = new DefaultTransactionDefinition();
        def.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        TransactionStatus status = txManager.getTransaction(def);
        Task waitTask = ConditionUtils.getInstance().createTask(kid);



        try {

            String compensateId = compensateService.saveTransactionInfo(info.getInvocation(), _groupId, kid);

            final Object res = point.proceed();

            task.setBack(new IBack() {
                @Override
                public Object doing(Object... objects) throws Throwable {
                    return res;
                }
            });
            //通知TxManager调用成功

            TxGroup txGroup = txManagerService.addTransactionGroup(_groupId, kid,false);
            //NotifyMsg notifyMsg = txManagerService.notifyTransactionInfo(_groupId, kid, true);
            if (txGroup == null) {
                //修改事务组状态异常
                txManager.rollback(status);
                compensateService.deleteTransactionInfo(compensateId);

                task.setBack(new IBack() {
                    @Override
                    public Object doing(Object... objects) throws Throwable {
                        throw new ServiceException("修改事务组状态异常.");
                    }
                });
                task.signalTask();

                return null;
            }

            ServiceThreadModel model = new ServiceThreadModel();
            model.setStatus(status);
            model.setWaitTask(waitTask);
            model.setTxGroup(txGroup);
            model.setCompensateId(compensateId);
            return model;


        } catch (final Throwable throwable) {
            task.setBack(new IBack() {
                @Override
                public Object doing(Object... objects) throws Throwable {
                    throw throwable;
                }
            });

            //修改事务组状态异常
            txManager.rollback(status);
            task.signalTask();

            return null;
        }
    }


    //等待线程
    private ScheduledFuture schedule(final ServiceThreadModel model, final String taskId, long time) {
        return  executorService.schedule(new Runnable() {
            @Override
            public void run() {
                Task task = ConditionUtils.getInstance().getTask(taskId);
                String groupId = model.getTxGroup().getGroupId();
                if (task.getState() == 0) {

                    int hasOk = txManagerService.checkTransactionInfo(groupId, taskId);
                    if (hasOk == 1) {
                        task.setState(1);
                        task.signalTask();
                    } else {
                        if (hasOk == -1) {
                            // 发起http请求查询状态
                            String json = HttpUtils.get(url + "Group?groupId=" + groupId + "&taskId=" + taskId);
                            if (json == null) {
                                //请求tm访问失败
                                task.setBack(new IBack() {
                                    @Override
                                    public Object doing(Object... objects) throws Throwable {
                                        return -100;//自动回滚补偿时也没有访问到tm
                                    }
                                });

                                task.signalTask();
                                return;

                            }
                            if (json.contains("true")) {

                                task.setState(1);
                                task.signalTask();

                                return;
                            }
                        }
                        task.setBack(new IBack() {
                            @Override
                            public Object doing(Object... objects) throws Throwable {
                                return -2;
                            }
                        });
                        logger.info("自定回滚执行");
                        task.signalTask();
                    }
                }
            }
        }, time, TimeUnit.MILLISECONDS);
    }


    public void serviceWait(final Task task, final ServiceThreadModel model) {
        final Task waitTask = model.getWaitTask();
        final String taskId = waitTask.getKey();
        TransactionStatus status = model.getStatus();

        long st = model.getTxGroup().getStartTime();
        long et =model.getTxGroup().getNowTime();

        int tmTime = model.getTxGroup().getWaitTime();

        long time = tmTime*1000 - ((int) (et - st));
        if (time <= 500) {
            //直接返回超时数据
            transactionConfirm(-2, waitTask, status, model, task);
            return;
        }

        //等待线程
        ScheduledFuture future =  schedule(model, taskId,time);


        task.signalTask();
        logger.info("返回业务数据");


        logger.info("进入回滚等待.");
        waitTask.awaitTask();

        //关闭自动回滚业务
        if(!future.isDone()){
            future.cancel(false);
        }

        try {
            int state = (Integer) waitTask.getBack().doing();
            logger.info("单元事务（1：提交 0：回滚 -1：事务模块网络异常回滚 -2：事务模块超时异常回滚）:" + state);
            //事务确认操作
            transactionConfirm(state, waitTask, status, model, task);
        } catch (Throwable throwable) {
            txManager.rollback(status);
        }
    }


    //事务确认状态
    private void transactionConfirm(int state, Task waitTask, TransactionStatus status, ServiceThreadModel model, Task task) {
        transactionLock(state, status, model.getCompensateId(), waitTask);

        if (state == -1) {
            task.setBack(new IBack() {
                @Override
                public Object doing(Object... objs) throws Throwable {
                    throw new Throwable("事务模块网络异常.");
                }
            });
        }
        if (state == -2) {
            task.setBack(new IBack() {
                @Override
                public Object doing(Object... objs) throws Throwable {
                    throw new Throwable("事务模块超时异常.");
                }
            });
        }

        if (state == -100) {
            //定时请求TM资源确认状态
            compensateService.addTask(model.getCompensateId());
        }

        //主程序的业务数据返回
        if (state!=1) {
            task.signalTask();
        }

    }

    //以下代码必须确保原子性
    private void transactionLock(int state, TransactionStatus status, String compensateId, Task waitTask) {
        try {
            if (state == 1) {
                txManager.commit(status);
            } else {
                txManager.rollback(status);
            }
        }finally {
            compensateService.deleteTransactionInfo(compensateId);
            if (waitTask != null)
                waitTask.remove();
        }
    }
}
