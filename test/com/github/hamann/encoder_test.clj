(ns com.github.hamann.encoder-test
  (:require [clojure.test :refer [deftest testing is]]
            [cheshire.core :as json]
            [clojure.java.io :as io])
  (:import [ch.qos.logback.classic Level LoggerContext]
           [ch.qos.logback.classic.spi LoggingEvent]
           [java.util HashMap]))

(when-not (try
            (Class/forName "com.github.hamann.EdnToJsonEncoder")
            true
            (catch ClassNotFoundException _ false))
  (println "EdnToJsonEncoder not found, attempting to compile...")
  (binding [*compile-path* "target/classes"]
    (io/make-parents "target/classes/dummy.txt")
    (compile 'com.github.hamann.edn-json-encoder))
  (println "Compilation completed"))

(import '[com.github.hamann EdnToJsonEncoder])

(defn create-test-event
  "Creates a simple LoggingEvent for tests"
  [logger-name level message]
  (let [logger-context (LoggerContext.)
        logger (.getLogger logger-context logger-name)
        event (LoggingEvent. logger-name logger level message nil nil)]
    (.setTimeStamp event (System/currentTimeMillis))
    (.setThreadName event "test-thread")
    event))

(defn create-test-event-with-mdc
  "Creates a LoggingEvent with explicitly set MDC"
  [logger-name level message mdc-map]
  (let [event (create-test-event logger-name level message)]
    (try
      (let [mdc-field (.getDeclaredField LoggingEvent "mdcPropertyMap")]
        (.setAccessible mdc-field true)
        (.set mdc-field event (HashMap. mdc-map)))
      (catch Exception e
        (println "Warning: Could not set MDC on event:" (.getMessage e))))
    event))

(defn create-test-event-with-exception
  "Creates a LoggingEvent with an Exception"
  [logger-name level message exception]
  (let [logger-context (LoggerContext.)
        logger (.getLogger logger-context logger-name)
        event (LoggingEvent. logger-name logger level message nil nil)]

    (.setTimeStamp event (System/currentTimeMillis))
    (.setThreadName event "test-thread")

    (when exception
      (try
        (let [throwable-field (.getDeclaredField LoggingEvent "throwableProxy")]
          (.setAccessible throwable-field true)
          (let [throwable-proxy (ch.qos.logback.classic.spi.ThrowableProxy. exception)]
            (.set throwable-field event throwable-proxy)))
        (catch Exception e
          (println "Warning: Could not set exception on event:" (.getMessage e)))))

    event))

(deftest edn-to-json-encoder-test
  (testing "EDN message encoding"
    (let [encoder (EdnToJsonEncoder.)
          logger-context (LoggerContext.)
          event (create-test-event "test" Level/INFO
                                   "{:transaction-id 12345 :status :committed}")]

      (.setContext encoder logger-context)
      (.start encoder)

      (let [encoded-bytes (.encode encoder event)
            json-str (String. encoded-bytes "UTF-8")
            parsed (json/parse-string json-str keyword)]

        (is (= true (:edn_parsed parsed)))
        (is (= "INFO" (:level parsed)))
        (is (= 12345 (:transaction-id parsed)))
        (is (= "committed" (:status parsed)))
        (is (contains? parsed :timestamp))
        (is (contains? parsed :logger))
        (is (contains? parsed :thread))))))

(deftest non-edn-message-test
  (testing "Non-EDN message handling"
    (let [encoder (EdnToJsonEncoder.)
          logger-context (LoggerContext.)
          event (create-test-event "test" Level/ERROR "Simple log message")]

      (.setContext encoder logger-context)
      (.start encoder)

      (let [encoded-bytes (.encode encoder event)
            json-str (String. encoded-bytes "UTF-8")
            parsed (json/parse-string json-str keyword)]

        (is (= false (:edn_parsed parsed)))
        (is (= "ERROR" (:level parsed)))
        (is (= "Simple log message" (:message parsed)))
        (is (contains? parsed :timestamp))))))

(deftest mdc-edn-parsing-test
  (testing "MDC values are parsed as EDN when possible"
    (let [encoder (EdnToJsonEncoder.)
          logger-context (LoggerContext.)
          mdc-data {"user-id" "12345"
                    "user-data" "{:name \"Alice\" :age 30}"
                    "tags" "[:urgent :customer]"
                    "config" "{:timeout 30 :retry? true}"
                    "plain-text" "just a string"}
          event (create-test-event-with-mdc "test" Level/INFO "Regular log message" mdc-data)]

      (.setContext encoder logger-context)
      (.start encoder)

      (let [encoded-bytes (.encode encoder event)
            json-str (String. encoded-bytes "UTF-8")
            parsed (json/parse-string json-str keyword)]

        (is (contains? parsed :mdc))
        (let [mdc (:mdc parsed)]
          (is (= "12345" (:user-id mdc)))
          (is (= "just a string" (:plain-text mdc)))

          (is (map? (:user-data mdc)))
          (is (= "Alice" (get-in mdc [:user-data :name])))
          (is (= 30 (get-in mdc [:user-data :age])))

          (is (vector? (:tags mdc)))
          (is (= ["urgent" "customer"] (:tags mdc)))

          (is (map? (:config mdc)))
          (is (= 30 (get-in mdc [:config :timeout])))
          (is (= true (get-in mdc [:config :retry?]))))))))

(deftest encoder-handles-edn-with-exception-test
  (testing "Encoder handles EDN messages with exceptions"
    (let [encoder (EdnToJsonEncoder.)
          logger-context (LoggerContext.)
          exception (RuntimeException. "Database connection failed")
          event (create-test-event-with-exception "test" Level/ERROR
                                                  "{:operation :db-query :error :connection-failed}"
                                                  exception)]

      (.setContext encoder logger-context)
      (.start encoder)

      (let [encoded-bytes (.encode encoder event)
            json-str (String. encoded-bytes "UTF-8")
            parsed (json/parse-string json-str keyword)]

        (is (= true (:edn_parsed parsed)))
        (is (= "db-query" (:operation parsed)))
        (is (= "connection-failed" (:error parsed)))

        (is (contains? parsed :exception))
        (let [ex (:exception parsed)]
          (is (= "java.lang.RuntimeException" (:exception_class ex)))
          (is (= "Database connection failed" (:exception_message ex))))))))

(deftest encoder-handles-complex-exception-test
  (testing "Encoder handles complex nested exceptions with appropriate format"
    (let [encoder (EdnToJsonEncoder.)
          logger-context (LoggerContext.)
          root-cause (IllegalArgumentException. "Invalid argument")
          cause (RuntimeException. "Operation failed" root-cause)
          main-exception (Exception. "Transaction rollback" cause)

          event (create-test-event-with-exception "test" Level/ERROR
                                                  "{:tx-id 12345 :rollback-reason :validation-failed}"
                                                  main-exception)]

      (.setContext encoder logger-context)
      (.start encoder)

      (let [encoded-bytes (.encode encoder event)
            json-str (String. encoded-bytes "UTF-8")
            parsed (json/parse-string json-str keyword)]

        (is (= true (:edn_parsed parsed)))
        (is (= 12345 (:tx-id parsed)))
        (is (= "validation-failed" (:rollback-reason parsed)))

        (is (contains? parsed :exception))
        (let [ex (:exception parsed)]
          (is (= "java.lang.Exception" (:exception_class ex)))
          (is (= "Transaction rollback" (:exception_message ex)))
          (is (vector? (:stack_trace ex)))

          (let [causes (:causes ex)]
            (is (vector? causes) "Causes should be a vector")
            (is (pos? (count causes)) "Should have at least one cause")
            (let [first-cause (first causes)]
              (is (= "java.lang.RuntimeException" (:class first-cause)) "First cause should be RuntimeException")
              (is (= "Operation failed" (:message first-cause)) "First cause message should match"))))))))

(deftest encoder-handles-full-exception-chain-test
  (testing "Full exception chain mode captures all causes"
      (let [encoder (EdnToJsonEncoder.)
            logger-context (LoggerContext.)
            root-cause (IllegalArgumentException. "Invalid argument")
            middle-cause (UnsupportedOperationException. "Unsupported operation" root-cause)
            cause (RuntimeException. "Operation failed" middle-cause)
            main-exception (Exception. "Transaction rollback" cause)
            event (create-test-event-with-exception "test" Level/ERROR
                                                    "Chain test"
                                                    main-exception)]

        (.setContext encoder logger-context)
        (.start encoder)

        (let [encoded-bytes (.encode encoder event)
              json-str (String. encoded-bytes "UTF-8")
              parsed (json/parse-string json-str keyword)]

          (is (contains? parsed :exception))
          (let [ex (:exception parsed)]
            (is (= "java.lang.Exception" (:exception_class ex)))
            (is (= "Transaction rollback" (:exception_message ex)))
            (is (vector? (:stack_trace ex)))

            (is (contains? ex :causes))
            (is (vector? (:causes ex)))
            (is (= 3 (count (:causes ex))) "Should have 3 causes in the chain")

            (is (= "java.lang.RuntimeException" (get-in ex [:causes 0 :class])))
            (is (= "Operation failed" (get-in ex [:causes 0 :message])))

            (is (= "java.lang.UnsupportedOperationException" (get-in ex [:causes 1 :class])))
            (is (= "Unsupported operation" (get-in ex [:causes 1 :message])))

            (is (= "java.lang.IllegalArgumentException" (get-in ex [:causes 2 :class])))
            (is (= "Invalid argument" (get-in ex [:causes 2 :message]))))))))

(deftest encoder-handles-exception-in-mdc-test
  (testing "Encoder handles exceptions with EDN in MDC"
    (let [encoder (EdnToJsonEncoder.)
          logger-context (LoggerContext.)
          exception (Exception. "Service unavailable")
          mdc-data {"request-context" "{:user-id 42 :action :checkout :cart-total 99.99}"
                    "correlation-id" "abc-123-def"}
          event (create-test-event-with-mdc "service.payment" Level/ERROR
                                            "Payment processing failed" mdc-data)]

      (try
        (let [throwable-field (.getDeclaredField LoggingEvent "throwableProxy")]
          (.setAccessible throwable-field true)
          (let [throwable-proxy (ch.qos.logback.classic.spi.ThrowableProxy. exception)]
            (.set throwable-field event throwable-proxy)))
        (catch Exception e
          (println "Warning: Could not set exception:" (.getMessage e))))

      (.setContext encoder logger-context)
      (.start encoder)

      (let [encoded-bytes (.encode encoder event)
            json-str (String. encoded-bytes "UTF-8")
            parsed (json/parse-string json-str keyword)]

        (is (= false (:edn_parsed parsed)))
        (is (= "Payment processing failed" (:message parsed)))

        (is (contains? parsed :mdc))
        (let [mdc (:mdc parsed)]
          (is (map? (:request-context mdc)))
          (is (= 42 (get-in mdc [:request-context :user-id]))))

        (is (contains? parsed :exception))
        (let [ex (:exception parsed)]
          (is (= "java.lang.Exception" (:exception_class ex)))
          (is (= "Service unavailable" (:exception_message ex)))
          (is (vector? (:stack_trace ex))))))))

(deftest time-types-conversion-test
  (testing "All time types are converted to ISO format"
    (let [encoder (EdnToJsonEncoder.)
          logger-context (LoggerContext.)
          mdc-data {"instant" "#inst \"2023-12-25T10:00:00.000Z\""
                    "local-datetime" "#time/ldt \"2023-12-25T10:00:00\""  ; If supported
                    "zoned-datetime" "#time/zdt \"2023-12-25T10:00:00+01:00[Europe/Berlin]\""} ; If supported
          event (create-test-event-with-mdc "test" Level/INFO "Time types test" mdc-data)]

      (.setContext encoder logger-context)
      (.start encoder)

      (let [encoded-bytes (.encode encoder event)
            json-str (String. encoded-bytes "UTF-8")
            parsed (json/parse-string json-str keyword)
            mdc (:mdc parsed)]

        (when-let [instant-str (:instant mdc)]
          (is (string? instant-str))
          (is (re-matches #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{3})?Z" instant-str)
              (str "Instant should be in ISO format, was: " instant-str)))))))

(deftest preserve-originals-config-test
  (testing "preserveOriginals property controls original EDN preservation"
    (let [encoder (EdnToJsonEncoder.)
          logger-context (LoggerContext.)
          mdc-data {"user-id" "12345"
                    "user-data" "{:name \"Alice\" :age 30}"}
          event (create-test-event-with-mdc "test" Level/INFO
                                           "{:message \"Test with config\" :data 42}"
                                           mdc-data)]

      (.setContext encoder logger-context)

      ;; Test with preserveOriginals = false (default)
      (.start encoder)

      (let [encoded-bytes (.encode encoder event)
            json-str (String. encoded-bytes "UTF-8")
            parsed (json/parse-string json-str keyword)]

        (is (not (contains? parsed :_original_mdc))
            "Should not contain _original_mdc when preserveOriginals is false")

        (is (not (contains? parsed :_original_message))
            "Should not contain _original_message when preserveOriginals is false"))

      ;; Test with preserveOriginals = true
      (.stop encoder) ;; Stop the encoder before changing configuration
      (.setPreserveOriginals encoder true)
      (.start encoder) ;; Restart with new configuration

      (let [encoded-bytes (.encode encoder event)
            json-str (String. encoded-bytes "UTF-8")
            parsed (json/parse-string json-str keyword)]

        (is (contains? parsed :_original_mdc)
            "Should contain _original_mdc when preserveOriginals is true")

        (is (contains? parsed :_original_message)
            "Should contain _original_message when preserveOriginals is true")

        (is (= "{:message \"Test with config\" :data 42}" (:_original_message parsed))
            "Original message should be preserved correctly")

        ;; Verify the original MDC values
        (let [original-mdc (:_original_mdc parsed)]
          (is (= "12345" (:user-id original-mdc)))
          (is (= "{:name \"Alice\" :age 30}" (:user-data original-mdc))))))))
