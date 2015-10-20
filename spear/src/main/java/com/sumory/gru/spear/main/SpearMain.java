package com.sumory.gru.spear.main;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

import com.sumory.gru.idgen.service.IdService;
import com.sumory.gru.spear.SpearContext;
import com.sumory.gru.spear.frontend.SpearServer;
import com.sumory.gru.spear.monitor.MonitorServer;
import com.sumory.gru.spear.task.UserStat;
import com.sumory.gru.spear.transport.IReceiver;
import com.sumory.gru.spear.transport.ISender;
import com.sumory.gru.spear.transport.inner.InnerReceiver;
import com.sumory.gru.spear.transport.inner.InnerSender;
import com.sumory.gru.spear.transport.rocketmq.RocketMQReceiver;
import com.sumory.gru.spear.transport.rocketmq.RocketMQSender;
import com.sumory.gru.spear.zk.ZkUtil;
import com.sumory.gru.stat.service.StatService;

/**
 * 启动入口
 * 
 * @author sumory.wu
 * @date 2015年1月14日 下午8:07:00
 */
public class SpearMain {
    private final static Logger logger = LoggerFactory.getLogger(SpearMain.class);

    public static void main(String[] args) {
        final SpearContext context = SpearContext.getInstance();
        Map<String, String> config = context.getConfig();

        //加载依赖的外部service
        try {
            @SuppressWarnings("resource")
            ApplicationContext appContext = new ClassPathXmlApplicationContext(new String[] {
                    "applicationContext.xml", "applicationContext-consumer-idgen.xml",
                    "applicationContext-consumer-stat.xml" });
            IdService idService = (IdService) appContext.getBean("idService");
            StatService statService = (StatService) appContext.getBean("statService");
            context.setIdService(idService);
            context.setStatService(statService);
            logger.info("idgen service version:{}, generate a demo id:{} ",
                    idService.getServiceVersion(), idService.getMsgId());
            logger.info("stat service version:{}", statService.getServiceVersion());
        }
        catch (Exception e) {
            logger.error("连接dubbo service出错", e);
            //System.exit(-1);
        }

        //启动zk，注册本节点
        try {
            int sessionTimeout = 3000;
            int retryTimes = 10;
            String zkHost = config.get("zk.addr");
            String baseNode = config.get("zk.spear.cluster");
            String outAddr = config.get("out.addr");//每个spear节点此值需唯一
            String spearId = config.get("spear.id");//每个spear节点此值需唯一
            ZkUtil.initNode(zkHost, baseNode, outAddr, spearId, sessionTimeout, retryTimes);
        }
        catch (Exception e) {
            logger.error("在zookeeper上新建spear节点出错", e);
            System.exit(-1);
        }

        //启动队列服务，启动节点长连接服务
        try {

            ISender sender;
            IReceiver receiver;
            String mode = config.get("mode");
            switch (mode) {
            case "inner":
                sender = new InnerSender(context);
                receiver = new InnerReceiver(context);
                break;
            case "roketmq":
                sender = new RocketMQSender(context);
                receiver = new RocketMQReceiver(context);
                break;
            default:
                sender = new InnerSender(context);
                receiver = new InnerReceiver(context);
            }

            context.setSender(sender);
            context.setReceiver(receiver);
            SpearServer spearServer = new SpearServer(context);

            sender.run();
            spearServer.run();

            boolean monitorStart = Boolean.parseBoolean(config.get("monitor.start"));
            if (monitorStart) {
                MonitorServer monitorServer = new MonitorServer(context);
                monitorServer.run();
            }
        }
        catch (Exception e) {
            logger.error("spear服务启动出错", e);
            System.exit(-1);
        }

        //启动节点信息统计上报服务
        try {
            UserStat userStat = new UserStat(context);
            userStat.run();
        }
        catch (Exception e) {
            logger.error("开始状态统计出错", e);
        }

        //死锁等待
        synchronized (SpearMain.class) {
            while (true) {
                try {
                    SpearMain.class.wait();
                }
                catch (Throwable e) {
                }
            }
        }
    }
}