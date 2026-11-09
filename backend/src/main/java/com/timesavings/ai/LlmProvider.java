package com.timesavings.ai;

/**
 * AI 공급자 하나(Anthropic, Gemini). AiClient 만 이 인터페이스를 쓴다.
 * 구현은 키 값을 로그·예외 메시지 어디에도 남기지 않는다.
 */
public interface LlmProvider {
    /** 'anthropic' | 'gemini' */
    String name();

    String model();

    /** 키가 들어 있어 부를 수 있는지 */
    boolean available();

    /**
     * system 지시 + user 문장을 보내 JSON 하나로 답하게 하고, 받은 글(text)을 그대로 돌려준다.
     * 네트워크·HTTP 오류(429 포함)는 예외로 던진다. 호출한 쪽(AiClient)이 규칙 결과로 바꾼다.
     */
    String completeJson(String system, String user, int maxTokens) throws Exception;
}
