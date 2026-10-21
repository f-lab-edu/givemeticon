package com.jinddung2.givemeticon.domain.coupon.mq;

import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** XML binding only. This does not execute SQL or prove MySQL CHECK/FK enforcement. */
class CouponMqMapperTest {
    @Test void mapperXmlBindsAuthenticatedIdentityAndFirstOutcomeWithoutDatabase() throws Exception {
        var configuration=new Configuration();
        for(String name:new String[]{"CouponMqControlMapper","CouponMqAttemptMapper"}) {
            String resource="mapper/"+name+".xml";
            try(var input=getClass().getClassLoader().getResourceAsStream(resource)) {
                assertNotNull(input);new XMLMapperBuilder(input,configuration,resource,configuration.getSqlFragments()).parse();
            }
        }
        String prefix="com.jinddung2.givemeticon.domain.coupon.mapper.";
        var query=configuration.getMappedStatement(prefix+"CouponMqAttemptMapper.find")
            .getBoundSql(Map.of("eventId",7L,"memberId",3,"attemptId","11111111-1111-4111-8111-111111111111"));
        assertEquals(java.util.List.of("eventId","memberId","attemptId"),query.getParameterMappings().stream().map(p->p.getProperty()).toList());
        var record=new CouponMqAttempt(7,3,"11111111-1111-4111-8111-111111111111","a".repeat(64),"b".repeat(64),null,
            "REJECTED",null,"OPEN_PROOF_MISSING",null);
        assertEquals(9,configuration.getMappedStatement(prefix+"CouponMqAttemptMapper.insert").getBoundSql(record).getParameterMappings().size());
    }
}
