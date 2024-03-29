package com.jinddung2.givemeticon.common.aop;

import org.aspectj.lang.ProceedingJoinPoint;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AopForTransaction {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Object proceed(ProceedingJoinPoint joinPoint) throws Throwable {
        joinPoint.proceed();
        return joinPoint.proceed();
    }
}
