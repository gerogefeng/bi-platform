/**
 * Copyright (c) 2014 Baidu, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.baidu.rigel.biplatform.tesseract.store.service.impl;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.EventObject;
import java.util.Properties;
import java.util.concurrent.locks.Lock;

import javax.annotation.Resource;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.stereotype.Service;

import com.baidu.rigel.biplatform.tesseract.store.service.HazelcastNoticePort;
import com.baidu.rigel.biplatform.tesseract.store.service.HazelcastQueueItemListener;
import com.baidu.rigel.biplatform.tesseract.store.service.StoreManager;
import com.baidu.rigel.biplatform.tesseract.util.isservice.LogInfoConstants;
import com.hazelcast.config.ClasspathXmlConfig;
import com.hazelcast.config.Config;
import com.hazelcast.config.JoinConfig;
import com.hazelcast.config.TcpIpConfig;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.core.IQueue;
import com.hazelcast.core.ITopic;
import com.hazelcast.spring.cache.HazelcastCacheManager;

/**
 * 
 * StoreManager 实现类 <li>动态加载hazelcast的配置启动hazelcast集群</li>
 * 
 * @author lijin
 */

// TODO 需要通过factory返回StoryManager的实例，不要直接用Spring的注解 --Add by xiaoming.chen
@Service("hazelcastStoreManager")
public class HazelcastStoreManager implements StoreManager,InitializingBean {
    
    public static final String DEFAULT_TESSERACT_CONFIG = "conf/tesseract.properties";

    public static final String EVENT_QUEUE = "eventQueue";

    private static final String HAZELCAST_SERVER_GROUP_PASSWORD = "hazelcastServer.groupPassword";

    private static final String HAZELCAST_SERVER_GROUP_USER_NAME = "hazelcastServer.groupUserName";

    private static final String HAZELCAST_SERVER_MEMBERS = "hazelcastServer.members";

    private static final Logger LOGGER = LoggerFactory.getLogger(HazelcastStoreManager.class);

    private static final String HAZELCAST_SERVER_NAME = "hazelcastServer.instance";

    private static final String HAZELCAST_MANCERTER_URL = "hazelcastServer.mancenter.url";
    
    
    
    /**
     * cacheManager
     */
    private CacheManager cacheManager;
    
    @Resource
    private HazelcastNoticePort hazelcastNoticePort;
    
    @Resource
    private HazelcastQueueItemListener hazelcastQueueItemListener;
    
    /**
     * hazelcast
     */
    private HazelcastInstance hazelcast;
    
    /**
     * 通过加载HZ的配置文件，动态创建HZ集群
     * 
     * @param configPath
     *            hz的配置文件
     */
    public HazelcastStoreManager(String configPath) {
        Config cfg = new ClasspathXmlConfig(configPath);
        
        Properties prop = new Properties();
        try {
            prop = loadConf(null);
        } catch (IOException e) {
            LOGGER.warn("load conf error,use default config");
        }
        cfg.getGroupConfig().setName(prop.getProperty(HAZELCAST_SERVER_GROUP_USER_NAME, "tesseract-cluster"));
        cfg.getGroupConfig().setPassword(prop.getProperty(HAZELCAST_SERVER_GROUP_PASSWORD, "tesseract"));
        
        cfg.setInstanceName(prop.getProperty(HAZELCAST_SERVER_NAME, "TesseractHZ_Cluster"));
        
       // cfg.getQueueConfig(EVENT_QUEUE).addItemListenerConfig(new ItemListenerConfig(this.hazelcastQueueItemListener,true));
        String manCenter = prop.getProperty(HAZELCAST_MANCERTER_URL);
        boolean enableManCerter = Boolean.valueOf(prop.getProperty("hazelcastServer.mancenter.enable"));
        if (enableManCerter && StringUtils.isNotBlank(manCenter)) {
            cfg.getManagementCenterConfig().setEnabled(true);
            cfg.getManagementCenterConfig().setUrl(manCenter);
        }
        System.setProperty("hazelcast.socket.bind.any", "false");
        String ip = "127.0.0.1";
        try {
            ip = InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            LOGGER.warn("get ip error, {}",e.getMessage());
        }
        LOGGER.info("local memchine ip: {}", ip);
        cfg.getProperties();//.getGroupProperties().SOCKET_SERVER_BIND_ANY
        cfg.getNetworkConfig().getInterfaces().addInterface(ip);
        cfg.getNetworkConfig().getInterfaces().setEnabled(true);
        
        JoinConfig join = cfg.getNetworkConfig().getJoin();
        TcpIpConfig tcpIpConfig = join.getTcpIpConfig();
        tcpIpConfig.addMember(prop.getProperty(HAZELCAST_SERVER_MEMBERS,"127.0.0.1"));
        tcpIpConfig.setEnabled(true);
        
        this.hazelcast = Hazelcast.newHazelcastInstance(cfg);
        this.cacheManager = new HazelcastCacheManager(this.hazelcast);
    }
    
    @Override
    public void afterPropertiesSet() throws Exception {
        this.hazelcast.getTopic("topics").addMessageListener(hazelcastNoticePort);
        IQueue<EventObject> queue = this.hazelcast.getQueue(EVENT_QUEUE);
        queue.addItemListener(hazelcastQueueItemListener,true);
    }
    
    private Properties loadConf(String location) throws IOException {
        if(StringUtils.isBlank(location)) {
            location = "config/application.properties";
            LOGGER.info("default load config from {}", location);
        }
        String filePath = this.getClass().getProtectionDomain().getCodeSource().getLocation().getFile();
        File propertiesFile = new File(new File(filePath).getParent(), location);
        Properties properties =null; 
        if(propertiesFile.exists()) {
            LOGGER.info("load from config {}", propertiesFile.getAbsolutePath());
            properties = new Properties();
            FileInputStream fis = null;
            try {
                fis = new FileInputStream(propertiesFile);
                properties.load(fis);
            } catch (IOException e) {
                LOGGER.warn("load config properties catch error",e);
            } finally {
                IOUtils.closeQuietly(fis);
            }
        }
        if (properties == null || properties.isEmpty()) {
            LOGGER.info("can not get default config from {} load config from {}", propertiesFile.getAbsolutePath(),DEFAULT_TESSERACT_CONFIG);
            properties = PropertiesLoaderUtils.loadAllProperties(DEFAULT_TESSERACT_CONFIG);
        }
        return properties;
    }
    
    /**
     * constructor 采用默认配置文件
     */
    public HazelcastStoreManager() {
        this("conf/applicationContext-hazelcast.xml");
    }
    
    @Override
    public Cache getDataStore(String name) {
        Cache cache = cacheManager.getCache(name);
        return cache;
    }
    
    /*
     * (non-Javadoc)
     * 
     * @see
     * com.baidu.rigel.biplatform.tesseract.store.service.StoreManager#putEvent
     * (java.util.EventObject)
     */
    @Override
    public void putEvent(EventObject event) throws Exception {
        
        LOGGER.info(String.format(LogInfoConstants.INFO_PATTERN_FUNCTION_BEGIN, "putEvent",
            "[event:" + event + "]"));
        if (event == null) {
            LOGGER.info(String.format(LogInfoConstants.INFO_PATTERN_FUNCTION_EXCEPTION, "putEvent",
                "[event:" + event + "]"));
            throw new IllegalArgumentException();
        }
        IQueue<EventObject> queue = this.hazelcast.getQueue(EVENT_QUEUE);
        try {
            queue.put(event);
        } catch (InterruptedException e) {
            LOGGER.info(String.format(LogInfoConstants.INFO_PATTERN_FUNCTION_EXCEPTION, "putEvent",
                "[event:" + event + "]"));
            throw e;
        }
        
        LOGGER.info(String.format(LogInfoConstants.INFO_PATTERN_FUNCTION_END, "putEvent", "[event:"
            + event + "]"));
    }
    
    /*
     * (non-Javadoc)
     * 
     * @see
     * com.baidu.rigel.biplatform.tesseract.store.service.StoreManager#getNextEvent
     * ()
     */
    @Override
    public EventObject getNextEvent() throws Exception {
        IQueue<EventObject> queue = this.hazelcast.getQueue(EVENT_QUEUE);
        return queue.take();
    }
    
    /*
     * (non-Javadoc)
     * 
     * @see
     * com.baidu.rigel.biplatform.tesseract.store.service.StoreManager#postEvent
     * (java.util.EventObject)
     */
    @Override
    public void postEvent(EventObject event) {
        LOGGER.info(String.format(LogInfoConstants.INFO_PATTERN_FUNCTION_BEGIN, "postEvent",
            "[event:" + event + "]"));
        if (event == null) {
            LOGGER.info(String.format(LogInfoConstants.INFO_PATTERN_FUNCTION_EXCEPTION,
                "postEvent", "[event:" + event + "]"));
            throw new IllegalArgumentException();
        }
        ITopic<Object> topics = this.hazelcast.getTopic("topics");
        
        topics.publish(event);
        
        LOGGER.info(String.format(LogInfoConstants.INFO_PATTERN_FUNCTION_END, "postEvent",
            "[event:" + event + "]"));
    }
    
    public Lock getClusterLock() {
        Lock lock = this.hazelcast.getLock("hzLock");
        
        return lock;
    }
    
}
