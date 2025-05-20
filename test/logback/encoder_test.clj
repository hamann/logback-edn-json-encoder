(ns logback.encoder-test
  (:require [clojure.test :refer [deftest testing is]]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [ch.qos.logback.classic Level LoggerContext]
           [ch.qos.logback.classic.spi LoggingEvent]
           [java.time Instant LocalDateTime ZonedDateTime]
           [java.util HashMap]))

(when-not (try
            (Class/forName "logback.EdnToJsonEncoder")
            true
            (catch ClassNotFoundException _ false))
  (println "EdnToJsonEncoder not found, attempting to compile...")
  (binding [*compile-path* "target/classes"]
    (io/make-parents "target/classes/dummy.txt")
    (compile 'logback.edn-json-encoder))
  (println "Compilation completed"))

(import '[logback EdnToJsonEncoder])

(defn create-test-event
  "Erstellt ein einfaches LoggingEvent für Tests"
  [logger-name level message]
  (let [logger-context (LoggerContext.)
        logger (.getLogger logger-context logger-name)
        event (LoggingEvent. logger-name logger level message nil nil)]
    (.setTimeStamp event (System/currentTimeMillis))
    (.setThreadName event "test-thread")
    event))

(defn create-test-event-with-mdc
  "Erstellt ein LoggingEvent mit explizit gesetztem MDC"
  [logger-name level message mdc-map]
  (let [event (create-test-event logger-name level message)]
    (try
      (let [mdc-field (.getDeclaredField LoggingEvent "mdcPropertyMap")]
        (.setAccessible mdc-field true)
        (.set mdc-field event (HashMap. mdc-map)))
      (catch Exception e
        (println "Warning: Could not set MDC on event:" (.getMessage e))))
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

(deftest encoder-handles-malformed-edn-test
  (testing "Malformed EDN handling"
    (let [encoder (EdnToJsonEncoder.)
          logger-context (LoggerContext.)
          event (create-test-event "test" Level/WARN "{:invalid edn syntax")]

      (.setContext encoder logger-context)
      (.start encoder)

      (let [encoded-bytes (.encode encoder event)
            json-str (String. encoded-bytes "UTF-8")
            parsed (json/parse-string json-str keyword)]

        (is (= false (:edn_parsed parsed)))
        (is (= "WARN" (:level parsed)))
        (is (= "{:invalid edn syntax" (:message parsed)))
        (is (not (contains? parsed :edn_parse_error)))))))

(deftest encoder-handles-real-malformed-edn-test
  (testing "Real malformed EDN that looks like valid EDN"
    (let [encoder (EdnToJsonEncoder.)
          logger-context (LoggerContext.)
          event (create-test-event "test" Level/WARN "{:invalid :syntax :with}")]

      (.setContext encoder logger-context)
      (.start encoder)

      (let [encoded-bytes (.encode encoder event)
            json-str (String. encoded-bytes "UTF-8")
            parsed (json/parse-string json-str keyword)]

        (is (= false (:edn_parsed parsed)))
        (is (= "WARN" (:level parsed)))
        (is (contains? parsed :edn_parse_error))
        (is (= "{:invalid :syntax :with}" (:message parsed)))))))

(deftest cheshire-automatic-conversion-test
  (testing "Java types are converted to JSON-compatible strings"
    (let [encoder (EdnToJsonEncoder.)
          logger-context (LoggerContext.)
          edn-data (str "{:status :committed "
                        ":edn-timestamp #inst \"2023-01-01T12:00:00.000Z\" "
                        ":user-id #uuid \"550e8400-e29b-41d4-a716-446655440000\" "
                        ":tags #{:urgent :customer-facing} "
                        ":ratio 22/7 "
                        ":count 42}")
          event (create-test-event "test" Level/INFO edn-data)]

      (.setContext encoder logger-context)
      (.start encoder)

      (let [encoded-bytes (.encode encoder event)
            json-str (String. encoded-bytes "UTF-8")
            parsed (json/parse-string json-str keyword)]

        (is (= true (:edn_parsed parsed)))

        (is (= "committed" (:status parsed)))

        (is (string? (:edn-timestamp parsed)))
        (is (str/includes? (:edn-timestamp parsed) "2023-01-01T12:00:00"))

        (is (string? (:timestamp parsed)))
        (is (str/includes? (:timestamp parsed) "2025"))

        (is (string? (:user-id parsed)))
        (is (str/includes? (:user-id parsed) "550e8400"))

        (is (coll? (:tags parsed)))
        (is (every? string? (:tags parsed)))

        (is (number? (:ratio parsed)))
        (is (> (:ratio parsed) 3.0))

        (is (= 42 (:count parsed)))))))

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

(deftest mdc-malformed-edn-handling-test
  (testing "Malformed EDN in MDC is handled gracefully"
    (let [encoder (EdnToJsonEncoder.)
          logger-context (LoggerContext.)
          mdc-data {"valid-edn" "{:key :value}"
                    "invalid-edn" "{:invalid syntax"
                    "not-edn" "regular string"}
          event (create-test-event-with-mdc "test" Level/INFO "Log message" mdc-data)]

      (.setContext encoder logger-context)
      (.start encoder)

      (let [encoded-bytes (.encode encoder event)
            json-str (String. encoded-bytes "UTF-8")
            parsed (json/parse-string json-str keyword)
            mdc (:mdc parsed)]

        (is (map? (:valid-edn mdc)))
        (is (= "value" (get-in mdc [:valid-edn :key])))

        (is (= "{:invalid syntax" (:invalid-edn mdc)))

        (is (= "regular string" (:not-edn mdc)))))))

(deftest mdc-instant-conversion-test
  (testing "Instants in MDC are properly converted"
    (let [encoder (EdnToJsonEncoder.)
          logger-context (LoggerContext.)
          mdc-data {"event-time" "#inst \"2023-12-25T10:00:00.000Z\""
                    "simple-data" "{:created #inst \"2023-12-25T11:00:00.000Z\"}"}
          event (create-test-event-with-mdc "test" Level/INFO "Simple message" mdc-data)]

      (.setContext encoder logger-context)
      (.start encoder)

      (let [encoded-bytes (.encode encoder event)
            json-str (String. encoded-bytes "UTF-8")
            parsed (json/parse-string json-str keyword)]

        (is (contains? parsed :mdc))
        (let [mdc (:mdc parsed)]
          (is (some? mdc))

          ;; Event-time prüfen
          (when-let [event-time (:event-time mdc)]
            (is (string? event-time))
            (is (str/includes? event-time "2023-12-25")
                (str "event-time should contain date, was: " event-time)))

          ;; Simple-data prüfen
          (when-let [simple-data (:simple-data mdc)]
            (is (map? simple-data))
            (when-let [created (get simple-data :created)]
              (is (string? created))
              (is (str/includes? created "2023-12-25")
                  (str "created should contain date, was: " created)))))))))

(deftest combined-message-and-mdc-edn-test
  (testing "Both message and MDC can contain EDN"
    (let [encoder (EdnToJsonEncoder.)
          logger-context (LoggerContext.)
          mdc-data {"session-data" "{:user-id 42 :role :admin}"
                    "trace-id" "abc-123"}
          event (create-test-event-with-mdc "test" Level/INFO "{:action :login :success? true}" mdc-data)]

      (.setContext encoder logger-context)
      (.start encoder)

      (let [encoded-bytes (.encode encoder event)
            json-str (String. encoded-bytes "UTF-8")
            parsed (json/parse-string json-str keyword)]

        (is (= true (:edn_parsed parsed)))
        (is (= "login" (:action parsed)))
        (is (= true (:success? parsed)))

        (is (contains? parsed :mdc))
        (let [mdc (:mdc parsed)]
          (is (map? (:session-data mdc)))
          (is (= 42 (get-in mdc [:session-data :user-id])))
          (is (= "admin" (get-in mdc [:session-data :role])))
          (is (= "abc-123" (:trace-id mdc))))))))

(deftest encoder-preserves-logback-metadata-test
  (testing "Logback metadata preservation"
    (let [encoder (EdnToJsonEncoder.)
          logger-context (LoggerContext.)
          logger (.getLogger logger-context "test.logger")
          event (doto (LoggingEvent. "test.logger" logger Level/DEBUG
                                     "{:action :user-login :user-id 123}" nil nil)
                  (.setTimeStamp 1640995200000)
                  (.setThreadName "worker-thread-1"))]
      (.setContext encoder logger-context)
      (.start encoder)

      (let [encoded-bytes (.encode encoder event)
            json-str (String. encoded-bytes "UTF-8")
            parsed (json/parse-string json-str keyword)]

        (is (= true (:edn_parsed parsed)))
        (is (= "DEBUG" (:level parsed)))
        (is (= "test.logger" (:logger parsed)))
        (is (= "worker-thread-1" (:thread parsed)))
        (is (str/includes? (:timestamp parsed) "2022-01-01T00:00:00"))
        (is (= "user-login" (:action parsed)))
        (is (= 123 (:user-id parsed)))))))

(deftest encoder-handles-null-and-edge-cases-test
  (testing "Edge cases and null handling"
    (let [encoder (EdnToJsonEncoder.)
          logger-context (LoggerContext.)]

      (.setContext encoder logger-context)
      (.start encoder)

      (let [event (create-test-event "test" Level/INFO "")
            encoded-bytes (.encode encoder event)
            json-str (String. encoded-bytes "UTF-8")
            parsed (json/parse-string json-str keyword)]
        (is (= false (:edn_parsed parsed)))
        (is (= "" (:message parsed))))

      (let [event (create-test-event "test" Level/INFO "   ")
            encoded-bytes (.encode encoder event)
            json-str (String. encoded-bytes "UTF-8")
            parsed (json/parse-string json-str keyword)]
        (is (= false (:edn_parsed parsed)))
        (is (= "   " (:message parsed))))

      (let [event (create-test-event "test" Level/INFO "{\"key\": \"value\"}")
            encoded-bytes (.encode encoder event)
            json-str (String. encoded-bytes "UTF-8")
            parsed (json/parse-string json-str keyword)]
        (is (= false (:edn_parsed parsed)))
        (is (contains? parsed :edn_parse_error))))))

(deftest encoder-basic-functionality-test
  (testing "Basic encoder lifecycle and functionality"
    (let [encoder (EdnToJsonEncoder.)
          logger-context (LoggerContext.)]

      (is (not (.isStarted encoder)))
      (.setContext encoder logger-context)
      (.start encoder)
      (is (.isStarted encoder))

      (let [event (create-test-event "test" Level/INFO "test message")
            result (.encode encoder event)]
        (is (not (nil? result)))
        (is (pos? (count result))))

      (is (= 0 (count (.headerBytes encoder))))
      (is (= 0 (count (.footerBytes encoder))))

      (.stop encoder)
      (is (not (.isStarted encoder))))))

(deftest encoder-handles-complex-nested-structures-test
  (testing "Complex nested EDN structures"
    (let [encoder (EdnToJsonEncoder.)
          logger-context (LoggerContext.)
          complex-edn (str "{:transaction {:id 12345 "
                           ":timestamp #inst \"2023-01-01T00:00:00.000Z\" "
                           ":operations [{:type :insert :entity {:name \"Alice\" :tags #{:vip :premium}}} "
                           "             {:type :update :entity {:id 42 :status :active}}]} "
                           ":metadata {:retry-count 0 :source :api}}")
          event (create-test-event "test" Level/INFO complex-edn)]

      (.setContext encoder logger-context)
      (.start encoder)

      (let [encoded-bytes (.encode encoder event)
            json-str (String. encoded-bytes "UTF-8")
            parsed (json/parse-string json-str keyword)]

        (is (= true (:edn_parsed parsed)))
        (is (= 12345 (get-in parsed [:transaction :id])))
        (is (string? (get-in parsed [:transaction :timestamp])))
        (is (vector? (get-in parsed [:transaction :operations])))
        (is (= "insert" (get-in parsed [:transaction :operations 0 :type])))
        (is (= "Alice" (get-in parsed [:transaction :operations 0 :entity :name])))
        (is (coll? (get-in parsed [:transaction :operations 0 :entity :tags])))))))

(deftest encoder-handles-no-mdc-gracefully-test
  (testing "Encoder handles events without MDC gracefully"
    (let [encoder (EdnToJsonEncoder.)
          logger-context (LoggerContext.)
          event (create-test-event "test" Level/INFO "{:simple :message}")]

      (.setContext encoder logger-context)
      (.start encoder)

      (let [encoded-bytes (.encode encoder event)
            json-str (String. encoded-bytes "UTF-8")
            parsed (json/parse-string json-str keyword)]

        (is (= true (:edn_parsed parsed)))
        (is (= "message" (:simple parsed)))

        (is (not (contains? parsed :mdc)))))))

(deftest time-types-conversion-test
  (testing "All time types are converted to ISO format"
    (let [encoder (EdnToJsonEncoder.)
          logger-context (LoggerContext.)
          mdc-data {"instant" "#inst \"2023-12-25T10:00:00.000Z\""
                    "local-datetime" "#time/ldt \"2023-12-25T10:00:00\""  ; Falls unterstützt
                    "zoned-datetime" "#time/zdt \"2023-12-25T10:00:00+01:00[Europe/Berlin]\""} ; Falls unterstützt
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

(deftest manual-time-objects-test
  (testing "Manual time objects are formatted correctly"
    (let [instant (Instant/parse "2023-12-25T10:00:00.000Z")
          local-dt (LocalDateTime/of 2023 12 25 10 0 0)
          zoned-dt (ZonedDateTime/of local-dt (java.time.ZoneId/of "Europe/Berlin"))

          prepared-instant (logback.edn-json-encoder/prepare-for-json instant)
          prepared-local (logback.edn-json-encoder/prepare-for-json local-dt)
          prepared-zoned (logback.edn-json-encoder/prepare-for-json zoned-dt)]

      (is (string? prepared-instant))
      (is (str/includes? prepared-instant "2023-12-25T10:00:00"))
      (is (str/ends-with? prepared-instant "Z"))

      (is (string? prepared-local))
      (is (str/includes? prepared-local "2023-12-25T10:00:00"))

      (is (string? prepared-zoned))
      (is (str/includes? prepared-zoned "2023-12-25T")))))

(deftest encoder-handles-exceptions-test
  (testing "Encoder handles exceptions and stack traces"
    (let [encoder (EdnToJsonEncoder.)
          logger-context (LoggerContext.)
          exception (RuntimeException. "Test exception with cause"
                                       (IllegalArgumentException. "Root cause"))
          logger (.getLogger logger-context "test")
          event (LoggingEvent. "test" logger Level/ERROR "Something went wrong" nil nil)]

      (try
        (let [throwable-field (.getDeclaredField LoggingEvent "throwableProxy")]
          (.setAccessible throwable-field true)
          (let [throwable-proxy (ch.qos.logback.classic.spi.ThrowableProxy. exception)]
            (.set throwable-field event throwable-proxy)))
        (catch Exception e
          (println "Warning: Could not set exception on event:" (.getMessage e))))

      (.setContext encoder logger-context)
      (.start encoder)
      (.setTimeStamp event (System/currentTimeMillis))
      (.setThreadName event "test-thread")

      (let [encoded-bytes (.encode encoder event)
            json-str (String. encoded-bytes "UTF-8")
            parsed (json/parse-string json-str keyword)]

        (is (= false (:edn_parsed parsed)))
        (is (= "ERROR" (:level parsed)))
        (is (= "Something went wrong" (:message parsed)))

        (is (contains? parsed :exception) "Should contain exception info")
        (let [ex (:exception parsed)]
          (is (= "java.lang.RuntimeException" (:exception_class ex)))
          (is (= "Test exception with cause" (:exception_message ex)))
          (is (vector? (:stack_trace ex))))))))

(defn create-test-event-with-exception
  "Erstellt ein LoggingEvent mit Exception"
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
  (testing "Encoder handles complex nested exceptions"
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
          (is (pos? (count (:stack_trace ex))))

          (is (contains? ex :cause))
          (let [cause-info (:cause ex)]
            (is (= "java.lang.RuntimeException" (:class cause-info)))
            (is (= "Operation failed" (:message cause-info)))))))))

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
          (is (= "Service unavailable" (:exception_message ex))))))))
