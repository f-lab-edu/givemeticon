package com.jinddung2.givemeticon.account.presentation.response;

public record OpenApiAccountRealNameDto(
        String pi_tran_id,
        String api_tran_dtm,
        String rsp_code,
        String rsp_message,
        String bank_tran_id,
        String bank_tran_date,
        String bank_code_tran,
        String bank_rsp_code,
        String bank_rsp_message,
        String bank_code_std,
        String bank_code_sub,
        String bank_name,
        String account_num,
        String account_holder_info_type,
        String account_holder_info,
        String account_holder_name,
        String account_type
) {
}
