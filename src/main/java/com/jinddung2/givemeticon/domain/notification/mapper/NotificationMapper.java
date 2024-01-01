package com.jinddung2.givemeticon.domain.notification.mapper;

import com.jinddung2.givemeticon.domain.notification.domain.Notification;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface NotificationMapper {
    int save(Notification notification);
}
