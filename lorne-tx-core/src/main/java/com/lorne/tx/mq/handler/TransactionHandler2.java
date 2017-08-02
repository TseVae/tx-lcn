package com.lorne.tx.mq.handler;

import com.alibaba.fastjson.JSONObject;
import com.lorne.core.framework.utils.task.ConditionUtils;
import com.lorne.core.framework.utils.task.IBack;
import com.lorne.core.framework.utils.task.Task;
import com.lorne.tx.mq.model.Request;
import com.lorne.tx.mq.service.NettyService;
import com.lorne.tx.utils.SocketUtils;
import com.lorne.tx.utils.ThreadPoolSizeHelper;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;

/**
 * Created by lorne on 2017/6/30.
 */
@ChannelHandler.Sharable
public class TransactionHandler2 extends ChannelInboundHandlerAdapter {

    /**
     * false 未链接
     * true 连接中
     */
    public static volatile boolean net_state = false;

    private Logger logger = LoggerFactory.getLogger(TransactionHandler2.class);

    /**
     * 自动返回数据时间，必须要小于事务模块最大相应时间.(通过心跳获取)
     */
    public static volatile int delay = 1;

    private ChannelHandlerContext ctx;

    private NettyService nettyService;

    private String heartJson;

    private Executor  threadPool = Executors.newFixedThreadPool(100);
    private ScheduledExecutorService executorService = Executors.newScheduledThreadPool(ThreadPoolSizeHelper.getInstance().getHandlerSize());


    public TransactionHandler2(NettyService nettyService, int delay) {
        this.nettyService = nettyService;
        this.delay = delay;

        //心跳包
        JSONObject heartJo = new JSONObject();
        heartJo.put("a", "h");
        heartJo.put("k", "h");
        heartJo.put("p", "{}");
        heartJson = heartJo.toString();

    }


    private void service(String json){
        if (StringUtils.isNotEmpty(json)) {
            JSONObject resObj = JSONObject.parseObject(json);
            if (resObj.containsKey("a")) {

                String action = resObj.getString("a");

                switch (action) {
                    case "t": {
                        //通知提醒
                        final int state = resObj.getInteger("c");
                        String taskId = resObj.getString("t");
                        String key = resObj.getString("k");
                        Task task = ConditionUtils.getInstance().getTask(taskId);
                        logger.info("接受通知数据->" + json);
                        String res = "";
                        if (task != null) {
                            int index = 0;
                            if(task.isAwait()) {   //已经等待
                                task.setState(state);
                                task.signalTask();
                                int count = 0;
                                while (true) {
                                    if (task.isRemove()) {
                                        res = "1";
                                        break;
                                    }
                                    if (count > 800) {
                                        res = "0";
                                        break;
                                    }

                                    count++;
                                    try {
                                        Thread.sleep(1);
                                    } catch (InterruptedException e) {
                                        e.printStackTrace();
                                    }
                                }
                            }else{
                                while (true) {
                                    if (index > 800) {
                                        res = "0";
                                        break;
                                    }
                                    index++;
                                    try {
                                        Thread.sleep(1);
                                    } catch (InterruptedException e) {
                                        e.printStackTrace();
                                    }
                                }
                            }
                        }
                        JSONObject data = new JSONObject();
                        data.put("k", key);
                        data.put("a", action);

                        JSONObject params = new JSONObject();
                        params.put("d", res);
                        data.put("p", params);

                        SocketUtils.sendMsg(ctx,data.toString());
                        logger.info("返回通知状态->" + data.toString());
                        break;
                    }
                }

            } else {
                String key = resObj.getString("k");
                if (!"h".equals(key)) {
                    final String data = resObj.getString("d");
                    Task task = ConditionUtils.getInstance().getTask(key);
                    if (task != null) {
                        if(task.isAwait()){
                            task.setBack(new IBack() {
                                @Override
                                public Object doing(Object... objs) throws Throwable {
                                    return data;
                                }
                            });
                            task.signalTask();
                        }
                    }
                }else{
                    final String data = resObj.getString("d");
                    if(StringUtils.isNotEmpty(data)){
                        try {
                            delay = Integer.parseInt(data);
                        }catch (Exception e){
                            delay = 1;
                        }
                    }
                }
            }
        }
    }


    @Override
    public void channelRead(ChannelHandlerContext ctx, final Object msg) throws Exception {
        net_state = true;
        final String json = SocketUtils.getJson(msg);
        logger.info("接受->" + json);

        threadPool.execute(new Runnable() {
            @Override
            public void run() {
                service(json);
            }
        });
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        cause.printStackTrace();
    }

    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
        super.channelUnregistered(ctx);
        net_state = false;
        //链接断开,重新连接
        nettyService.close();
        Thread.sleep(1000 * 3);
        nettyService.start();
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
        this.ctx = ctx;
        logger.info("建立链接-->" + ctx);
        net_state = true;
    }


    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        //心跳配置
        if (IdleStateEvent.class.isAssignableFrom(evt.getClass())) {
            IdleStateEvent event = (IdleStateEvent) evt;
            if (event.state() == IdleState.READER_IDLE) {
                //表示已经多久没有收到数据了
                //ctx.close();
            } else if (event.state() == IdleState.WRITER_IDLE) {
                //表示已经多久没有发送数据了
                SocketUtils.sendMsg(ctx,heartJson);
                logger.info("心跳数据---" + heartJson);
            } else if (event.state() == IdleState.ALL_IDLE) {
                //表示已经多久既没有收到也没有发送数据了

            }
        }
    }

    private void sleepSend(Task task, Request request){
        while (!task.isAwait()&&!Thread.currentThread().interrupted()){
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        SocketUtils.sendMsg(ctx,request.toMsg());
        logger.info("send-msg->"+request.toMsg());
    }

    public String sendMsg(final Request request) {
        final String key = request.getKey();
        if (ctx != null && ctx.channel() != null && ctx.channel().isActive()) {

            final Task task = ConditionUtils.getInstance().createTask(key);
            ScheduledFuture future =  executorService.schedule(new Runnable() {
                @Override
                public void run() {
                    Task task = ConditionUtils.getInstance().getTask(key);
                    logger.info("sendMsg-schedule->"+request.getKey());
                    if (task != null && !task.isNotify()) {
                        task.setBack(new IBack() {
                            @Override
                            public Object doing(Object... objs) throws Throwable {
                                return null;
                            }
                        });
                        task.signalTask();
                        logger.info("sendMsg-schedule-signalTask->"+request.getKey());
                    }
                }
            }, delay, TimeUnit.SECONDS);


            threadPool.execute(new Runnable() {
                @Override
                public void run() {
                    sleepSend(task,request);
                }
            });


            task.awaitTask();

            if(!future.isDone()){
                future.cancel(false);
            }

            try {
                Object msg =  task.getBack().doing();
                return (String) msg;
            } catch (Throwable e) {
            }finally {
                task.remove();
            }


        }
        return null;

    }
}