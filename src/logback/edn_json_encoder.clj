(ns logback.edn-json-encoder
  (:require [clojure.edn :as edn]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.walk :as walk])
  (:import [java.time Instant]
           [java.time.format DateTimeFormatter]
           [ch.qos.logback.classic.spi ILoggingEvent]
           [java.nio.charset StandardCharsets]))

(gen-class
  :name logback.EdnToJsonEncoder
  :extends ch.qos.logback.core.encoder.EncoderBase
  :methods [[encode [ch.qos.logback.classic.spi.ILoggingEvent] "[B"]]
  :init init
  :state state)

(defn -init
  "Initialize the encoder with an empty state atom"
  []
  [[] (atom {:context nil :started false :preserve-originals false})])

(def iso-formatter DateTimeFormatter/ISO_INSTANT)
(def iso-local-formatter DateTimeFormatter/ISO_LOCAL_DATE_TIME)
(def iso-zoned-formatter DateTimeFormatter/ISO_ZONED_DATE_TIME)

(def ^:dynamic *preserve-originals*
  (Boolean/parseBoolean (System/getProperty "logback.edn-json-encoder.preserve-originals" "false")))

(defn prepare-for-json
  "Converts some problematic java types"
  [data]
  (walk/postwalk
   (fn [x]
     (cond
       (instance? java.time.Instant x)
       (.format iso-formatter x)

       (instance? java.time.LocalDateTime x)
       (.format iso-local-formatter x)

       (instance? java.time.ZonedDateTime x)
       (.format iso-zoned-formatter x)

       (instance? java.util.Date x)
       (.format iso-formatter (.toInstant x))

       (instance? java.net.URI x) (str x)
       (instance? java.io.File x) (.getPath x)
       (instance? java.util.regex.Pattern x) (str x)

       (instance? java.util.Map x) (into {} x)
       (instance? java.util.List x) (vec x)
       (instance? java.util.Set x) (vec x)

       :else x))
   data))

(defn extract-exception-info
  "Extracts exception information"
  [^ILoggingEvent event]
  (when-let [throwable (.getThrowableProxy event)]
    (try
      {:exception_class (.getClassName throwable)
       :exception_message (.getMessage throwable)
       :stack_trace (->> (.getStackTraceElementProxyArray throwable)
                        (take 10)
                        (map #(str (.toString (.getStackTraceElement %))))
                        vec)
       :cause (when-let [cause (.getCause throwable)]
                {:class (.getClassName cause)
                 :message (.getMessage cause)})}
      (catch Exception e
        {:exception_error (.getMessage e)}))))

(defn parse-edn-value-safely
  "Tries to parse a string as EDN"
  [value]
  (if (string? value)
    (try
      (let [trimmed (str/trim value)]
        (cond
          (str/blank? trimmed) value

          (or (str/starts-with? trimmed "{")
              (str/starts-with? trimmed "[")
              (str/starts-with? trimmed "#{")
              (str/starts-with? trimmed "#inst")
              (str/starts-with? trimmed "#uuid"))
          (edn/read-string trimmed)

          (str/starts-with? trimmed ":")
          (edn/read-string trimmed)

          :else value))
      (catch Exception _e
        value))
    value))

(defn parse-edn-safely
  "Safely parse EDN string, returning structured data or error info"
  [message]
  (try
    (if (and (string? message)
             (str/starts-with? (str/trim message) "{")
             (str/ends-with? (str/trim message) "}"))
      (assoc (edn/read-string message) :edn_parsed true)
      {:message message :edn_parsed false})
    (catch Exception e
      {:message message
       :edn_parse_error (.getMessage e)
       :edn_parsed false})))

(defn safe-get-mdc-with-edn-parsing
  "Gets MDC context and parses it"
  [event preserve-originals?]
  (try
    (when-let [mdc (.getMDCPropertyMap event)]
      (let [parsed-mdc (reduce-kv
                         (fn [acc k v] (assoc acc (keyword k) (parse-edn-value-safely v)))
                         {}
                         mdc)]
       (cond-> {:parsed parsed-mdc}
         preserve-originals? (assoc :original (into {} mdc)))))
      (catch Exception _e
       nil)))

(defn -doEncode
  "Encode a LoggingEvent to JSON bytes"
  [this event]
  (when (.isStarted this)
    (try
      (let [preserve-originals? *preserve-originals*
            original-message (.getFormattedMessage event)
            parsed-message (parse-edn-safely original-message)
            mdc-result (safe-get-mdc-with-edn-parsing event preserve-originals?)
            exception-info (extract-exception-info event)
            base-metadata {:timestamp (Instant/ofEpochMilli (.getTimeStamp event))
                           :level (str (.getLevel event))
                           :logger (.getLoggerName event)
                           :thread (.getThreadName event)}
            enriched-data (cond-> (merge parsed-message base-metadata)

                           (and mdc-result (:parsed mdc-result))
                           (assoc :mdc (:parsed mdc-result))

                           (and preserve-originals? mdc-result (:original mdc-result))
                           (assoc :_original_mdc (:original mdc-result))

                           exception-info
                           (assoc :exception exception-info))
            json-ready-data (prepare-for-json enriched-data)
            json-line (str (json/generate-string json-ready-data) \newline)]
      (.getBytes json-line StandardCharsets/UTF_8))
    (catch Exception e
      (.getBytes (str "ERROR: " (.getMessage e)) StandardCharsets/UTF_8)))))

;; interface methods
#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn -encode [this event] (-doEncode this event))
#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn -headerBytes [_this] (byte-array 0))
#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn -footerBytes [_this] (byte-array 0))
