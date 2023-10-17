package com.jinddung2.givemeticon.account.application;

import com.jinddung2.givemeticon.account.domain.Account;
import com.jinddung2.givemeticon.account.exception.MisMatchBank;
import com.jinddung2.givemeticon.account.exception.MisMatchRealName;
import com.jinddung2.givemeticon.account.infrastructure.mapper.AccountMapper;
import com.jinddung2.givemeticon.account.presentation.response.OpenApiAccountRealNameDto;
import com.jinddung2.givemeticon.account.presentation.response.OpenBankingToken;
import com.jinddung2.givemeticon.common.exception.ApiRequestFailedException;
import com.jinddung2.givemeticon.common.exception.UnauthorizedUserException;
import com.jinddung2.givemeticon.common.utils.CertificationGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.configurationprocessor.json.JSONException;
import org.springframework.boot.configurationprocessor.json.JSONObject;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class OpenBankingService {
    private final String OPENAPI_DEFAULT_URI = "https://testapi.openbanking.or.kr";
    private final int OPEN_BANKING_VERIFICATION_LIMIT_IN_SECONDS = 60 * 15;
    private final RestTemplate restTemplate;
    private final RedisTemplate<String, Object> redisTemplate;
    private final CertificationGenerator certificationGenerator;
    private final AccountMapper accountMapper;

    @Value("${openapi.client-id}")
    private String clientId;
    @Value("${openapi.client-secret}")
    private String clientSecret;

    public OpenBankingService(RestTemplate restTemplate,
                              RedisTemplate<String, Object> redisTemplate,
                              CertificationGenerator certificationGenerator,
                              AccountMapper accountMapper
    ) {
        this.restTemplate = restTemplate;
        this.redisTemplate = redisTemplate;
        this.certificationGenerator = certificationGenerator;
        this.accountMapper = accountMapper;
    }

    public OpenBankingToken requestAccessToken() {
        URI uri = URI.create(OPENAPI_DEFAULT_URI + "/oauth/2.0/token");

        HttpEntity<MultiValueMap<String, String>> request = generateTokenHttpRequest();

        OpenBankingToken openBankingToken = null;

        try {
            openBankingToken = restTemplate.postForObject(uri, request, OpenBankingToken.class);
        } catch (HttpClientErrorException | HttpServerErrorException e) {
            log.error("HTTP Error={}", e.getStatusText());
            log.error("Response body={}", e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("OpenBankingService.openBanking() error occurred={} ", e.getMessage(), e);
        }

        if (openBankingToken == null) {
            throw new ApiRequestFailedException();
        }

        redisTemplate.opsForValue().set(openBankingToken.client_use_code(),
                openBankingToken.access_token(),
                OPEN_BANKING_VERIFICATION_LIMIT_IN_SECONDS,
                TimeUnit.SECONDS);

        return openBankingToken;
    }

    public int requestMatchRealName(String accessToken, String transactionId, String bankCode, String accountNum,
                                    String realName, String birthday) throws JSONException, NoSuchAlgorithmException {
        URI uri = URI.create(OPENAPI_DEFAULT_URI + "/v2.0/inquiry/real_name");
        HttpEntity<String> request =
                generateRealNameHttpRequest(accessToken, transactionId, bankCode, accountNum, birthday);

        log.info("OpenBankingService.requestMatchRealName(): httpRequest={}", request);
        OpenApiAccountRealNameDto realNameDto = null;
        try {
            realNameDto = restTemplate.postForObject(uri, request, OpenApiAccountRealNameDto.class);
        } catch (HttpClientErrorException | HttpServerErrorException e) {
            log.error("HTTP Error={}", e.getStatusText());
            log.error("Response body={}", e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("OpenBankingService.openBanking() error occurred={} ", e.getMessage(), e);
        }

        log.info("service: realNameDto={}", realNameDto);
        if (realNameDto == null) {
            throw new ApiRequestFailedException();
        }

        if (!Objects.equals(realNameDto.bank_code_std(), bankCode)) {
            log.error("transactionId={}, bankCode={}", realNameDto.bank_tran_id(), realNameDto.bank_code_std());
            throw new MisMatchBank();
        }

        if (!Objects.equals(realNameDto.account_holder_name(), realName)) {
            log.error("transactionId={}, realName={}", realNameDto.bank_tran_id(), realNameDto.account_holder_name());
            throw new MisMatchRealName();
        }

        if (!Objects.equals(realNameDto.account_holder_info(), birthday)) {
            log.error("transactionId={}, birthday={}", realNameDto.bank_tran_id(), realNameDto.account_holder_info());
        }

        Optional<Account> account = accountMapper.findByTransactionId(realNameDto.bank_tran_id());
        int accountId = 0;

        if (account.isEmpty()) {
            accountId = accountMapper.save(Account.builder()
                    .transactionId(realNameDto.bank_tran_id())
                    .accountHolder(realNameDto.account_holder_name())
                    .accountNumber(realNameDto.account_num())
                    .bankName(realNameDto.bank_name())
                    .birth(realNameDto.account_holder_info())
                    .build());
        }
        return accountId;
    }

    private HttpEntity<MultiValueMap<String, String>> generateTokenHttpRequest() {
        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        httpHeaders.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("client_id", clientId);
        body.add("client_secret", clientSecret);
        body.add("scope", "oob");
        body.add("grant_type", "client_credentials");

        return new HttpEntity<>(body, httpHeaders);
    }

    /*
     * JsonObject.put(String key, JsonElement value): value 값을 JsonElement(JsonPrimitive, JsonObject, JsonArray, JsonNull) 객체로 다룰 수 있다.
     * JsonObject.addProperty(String key, String value): 값이 문자열일 경우 JsonPrimitive(문자열, 숫자, 리터럴, null)로 간주한다.
     */
    private HttpEntity<String> generateRealNameHttpRequest(String accessToken, String transactionId, String bankCode,
                                                           String accountNum, String birthday)
            throws JSONException, NoSuchAlgorithmException {
        String accessTok = accessToken.substring(7);
        String token = (String) redisTemplate.opsForValue().get(transactionId);

        if (!accessTok.equals(token)) {
            log.info("금융결제원 api 토큰 미일치");
            throw new UnauthorizedUserException();
        }

        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.setContentType(MediaType.APPLICATION_JSON);
        httpHeaders.setBearerAuth(token);
        httpHeaders.setAcceptCharset(Collections.singletonList(StandardCharsets.UTF_8));
        httpHeaders.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));

        int randomNum = certificationGenerator.createRandomNumber(999999999);

        JSONObject params = new JSONObject();
        params.put("bank_code_std", bankCode);
        params.put("account_num", accountNum);
        params.put("account_holder_info_type", "");
        params.put("account_holder_info", birthday);
        params.put("bank_tran_id", transactionId + "U" + randomNum);
        params.put("tran_dtime", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")));
        return new HttpEntity<>(params.toString(), httpHeaders);
    }
}
