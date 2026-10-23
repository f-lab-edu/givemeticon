package com.jinddung2.couponlab.coupon.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import com.jinddung2.couponlab.coupon.model.CouponStock;

/**
 * Guards the couponStockMap constructor mapping without a database: MyBatis resolves javaType "long"/"int" to the
 * wrapper classes, which do not match the record's (long, int, int) constructor; "_long"/"_int" do.
 */
class CouponStockResultMapTest {

    private static final String MAPPER_XML = "mapper/CouponMapper.xml";

    @Test
    void constructorArgumentsMatchTheRecordConstructor() throws IOException {
        Configuration configuration = loadMapper();
        ResultMap resultMap = configuration.getResultMap("com.jinddung2.couponlab.coupon.mapper.CouponMapper.couponStockMap");

        List<Class<?>> argTypes = resultMap.getConstructorResultMappings().stream()
                .<Class<?>>map(ResultMapping::getJavaType)
                .toList();

        assertThat(argTypes).containsExactly(long.class, int.class, int.class);
        CouponStock stock = configuration.getObjectFactory().create(CouponStock.class, argTypes, List.of(1L, 100, 100));
        assertThat(stock).isEqualTo(new CouponStock(1L, 100, 100));
    }

    private static Configuration loadMapper() throws IOException {
        Configuration configuration = new Configuration();
        try (InputStream in = Resources.getResourceAsStream(MAPPER_XML)) {
            new XMLMapperBuilder(in, configuration, MAPPER_XML, configuration.getSqlFragments()).parse();
        }
        return configuration;
    }
}
