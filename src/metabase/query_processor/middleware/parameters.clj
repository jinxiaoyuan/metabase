(ns metabase.query-processor.middleware.parameters
  "Middleware for substituting parameters in queries."
  (:require [clojure.data :as data]
            [clojure.tools.logging :as log]
            [schema.core :as s]
            [metabase.driver.generic-sql.util.unprepare :as unprepare]
            [clojure.string :as str]
            [metabase.query-processor
             [interface :as i]
             [util :as qputil]]
            [metabase.query-processor.middleware.parameters
             [mbql :as mbql-params]
             [sql :as sql-params]]
            [metabase.util :as u]))

(defn- is-druid-query [query-dict] (and (= "native" (str (get-in query-dict [:type]))) (str/includes? (str (get-in query-dict [:driver])) "ruid") (> (count (get-in query-dict [:parameters])) 0)))

(defn- expand-parameters*
  "Expand any :parameters set on the QUERY-DICT and apply them to the query definition.
   This function removes the :parameters attribute from the QUERY-DICT as part of its execution."
  [{:keys [parameters], :as query-dict}]
  (if (is-druid-query query-dict)
    (sql-params/expand-druid query-dict)
    (if (qputil/mbql-query? query-dict)
      (mbql-params/expand (dissoc query-dict :parameters) parameters)
      (sql-params/expand query-dict))))

  ; (update query-dict :native expand-druid-query-params (sql-params/query->params-map query-dict)))
  ;; params in native queries are currently only supported for SQL drivers

(defn- expand-params-in-native-source-query
  "Expand parameters in a native source query."
  [{{{original-query :native, tags :template_tags} :source-query} :query, :as outer-query}]
  ;; TODO - This isn't recursive for nested-nested queries
  ;; TODO - Yes, this approach is hacky. But hacky & working > not working
  (let [{{new-query :query, new-params :params} :native} (sql-params/expand (assoc outer-query
                                                                                   :type   :native
                                                                                   :native {:query         original-query
                                                                                            :template_tags tags}))]
    (if (= original-query new-query)
      ;; if the native query didn't change, we don't need to do anything; return as-is
      outer-query
      ;; otherwise replace the native query with the param-substituted version.
      ;; 'Unprepare' the args because making sure args get passed in the right order is too tricky for nested queries
      ;; TODO - This might not work for all drivers. We should make 'unprepare' a Generic SQL method
      ;; so different drivers can invoke unprepare/unprepare with the correct args
      (-> outer-query
          (assoc-in [:query :source-query :native] (unprepare/unprepare (cons new-query new-params)))))))

(defn- expand-parameters
  "Expand parameters in the OUTER-QUERY, and if the query is using a native source query, expand params in that as well."
  [outer-query]
  (cond-> (expand-parameters* outer-query)
    (get-in outer-query [:query :source-query :native]) expand-params-in-native-source-query))

(defn- substitute-parameters*
  "If any parameters were supplied then substitute them into the query."
  [query]
  (log/info (u/format-color 'red "begin substitute-parameters:\n%s" query))
  (u/prog1 (expand-parameters query)
           (log/info (u/format-color 'red "end expand-parameters:\n%s" query))
           (when (and (not i/*disable-qp-logging*)
                      (not= <> query))
             (when-let [diff (second (data/diff query <>))]
               (log/debug (u/format-color 'cyan "\n\nPARAMS/SUBSTITUTED: %s\n%s" (u/emoji "😻") (u/pprint-to-str diff)))))))

(defn substitute-parameters
  "Substitute Dashboard or Card-supplied parameters in a query, replacing the param placeholers
   with appropriate values and/or modifiying the query as appropriate.

   (e.g. a SQL query with a param like `{{param}}` will have that part of the query replaced with an appropriate
   snippet as well as any prepared statement args needed.)"
  [qp]
  (comp qp substitute-parameters*))
