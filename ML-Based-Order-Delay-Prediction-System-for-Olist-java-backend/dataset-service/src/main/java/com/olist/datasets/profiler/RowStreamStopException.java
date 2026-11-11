package com.olist.datasets.profiler;

/**
 * 流式读取的中止信号。消费方达到自身窗口上限时抛出，reader 负责原样透传，
 * 不得包装成通用读取异常。
 */
public class RowStreamStopException extends RuntimeException {

    public RowStreamStopException() {
        super("row stream stopped by consumer");
    }
}
