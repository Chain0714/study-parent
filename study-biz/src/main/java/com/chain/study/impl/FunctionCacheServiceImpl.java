package com.chain.study.impl;

import com.chain.study.dao.FunctionDao;
import com.chain.study.dmo.FunctionDmo;
import com.chain.study.intf.FunctionCacheService;
import com.google.common.cache.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Created by 17032651 on 2017/6/19.
 */
@Service
public class FunctionCacheServiceImpl implements FunctionCacheService {

    private Logger log = LoggerFactory.getLogger(FunctionCacheServiceImpl.class);
    @Autowired
    private FunctionDao functionDao;

    public FunctionDmo getFunctionCache(String name) {
        //缓存接口这里是LoadingCache，LoadingCache在缓存项不存在时可以自动加载缓存
        LoadingCache<String, FunctionDmo> functionCache
                //CacheBuilder的构造函数是私有的，只能通过其静态方法newBuilder()来获得CacheBuilder的实例
                = CacheBuilder.newBuilder()
                //设置并发级别为8，并发级别是指可以同时写缓存的线程数
                .concurrencyLevel(8)
                //设置写缓存后8秒钟过期
                .expireAfterWrite(60, TimeUnit.SECONDS)
                //设置缓存容器的初始容量为10
                .initialCapacity(10)
                //设置缓存最大容量为100，超过100之后就会按照LRU最近虽少使用算法来移除缓存项
                .maximumSize(100)
                //设置要统计缓存的命中率
                .recordStats()
                //设置缓存的移除通知
                .removalListener(new RemovalListener<Object, Object>() {
                    public void onRemoval(RemovalNotification<Object, Object> notification) {
                        System.out.println(
                                notification.getKey() + " was removed, cause is " + notification
                                        .getCause());
                    }
                })
                //build方法中可以指定CacheLoader，在缓存不存在时通过CacheLoader的实现自动加载缓存
                .build(new CacheLoader<String, FunctionDmo>() {
                    @Override
                    public FunctionDmo load(String key) {
                        return functionDao.getFunctionByName(key);
                    }
                });
        try {
            log.info("从缓存获取函数:{}",name);
            return functionCache.get(name);
        } catch (Exception e) {
            log.info("从缓存获取失败:{}",name);
        }
        return null;

    }

}
