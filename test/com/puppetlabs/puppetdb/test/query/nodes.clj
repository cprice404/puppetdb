(ns com.puppetlabs.puppetdb.test.query.nodes
  (:require [clojure.set :as set]
            [com.puppetlabs.puppetdb.query.nodes :as node]
            [clojure.java.jdbc :as sql])
  (:use clojure.test
        [clj-time.core :only [now ago days minus]]
        [clj-time.coerce :only [to-timestamp]]
        [clojure.math.combinatorics :only [combinations]]
        [com.puppetlabs.puppetdb.fixtures]))

(use-fixtures :each with-test-db)

(defn- raw-retrieve-nodes
  [version filter-expr paging-options]
  (let [sql (node/query->sql version filter-expr)]
    (node/query-nodes version sql paging-options)))

(defn- retrieve-node-names
  ([version filter-expr] (retrieve-node-names version filter-expr {}))
  ([version filter-expr paging-options]
   (->> (raw-retrieve-nodes version filter-expr paging-options)
        (:result)
        (mapv :name))))

(def names #{"node_a" "node_b" "node_c" "node_d" "node_e"})

(defn- combination-tests
  [versions test-cases]
  (doseq [version versions
          size (range 1 (inc (count test-cases)))
          terms (combinations test-cases size)
          :let [exprs      (map first terms)
                results    (map (comp set last) terms)
                and-expr   (cons "and" exprs)
                and-result (apply set/intersection results)
                or-expr    (cons "or" exprs)
                or-result  (apply set/union results)
                not-expr   ["not" (cons "or" exprs)]
                not-result (apply set/difference names results)]]
    (testing (str "for version " version)
      (is (= (set (retrieve-node-names version and-expr))
             (set and-result))
          (format "%s => %s" and-expr and-result))
      (is (= (set (retrieve-node-names version or-expr))
             (set or-result))
          (format "%s => %s" or-expr or-result))
      (is (= (set (retrieve-node-names version not-expr))
             (set not-result))
          (format "%s => %s" not-expr not-result)))))

(deftest query-nodes
  (let [timestamp (to-timestamp (now))]
    (sql/insert-records
     :environments
     {:name "production"})

    (doseq [name names]
      (sql/insert-record :certnames {:name name})
      (sql/insert-record :certname_facts_metadata {:certname name :timestamp timestamp :environment_id 1}))

    (sql/insert-records
      :certname_facts
      {:certname "node_a" :name "kernel" :value "Linux"}
      {:certname "node_b" :name "kernel" :value "Linux"}
      {:certname "node_c" :name "kernel" :value "Darwin"}
      {:certname "node_d" :name "uptime_seconds" :value "10000"})

    (let [test-cases {["=" ["fact" "kernel"] "Linux"]
                      #{"node_a" "node_b"}
                      ["=" ["fact" "kernel"] "Darwin"]
                      #{"node_c"}
                      ["=" ["fact" "kernel"] "Nothing"]
                      #{}
                      ["=" ["fact" "uptime"] "Linux"]
                      #{}
                      ["=" ["fact" "uptime_seconds"] "10000"]
                      #{"node_d"}}]
      (combination-tests [:v2 :v3 :v4] test-cases))

    (let [test-cases {["=" "facts-environment" "production"]
                      #{"node_a" "node_b" "node_c" "node_d" "node_e"}}]
      (combination-tests [:v4] test-cases))))

(deftest paging-results
  (let [right-now (now)]
    (doseq [[id node facts-age catalog-age] [[1 "node_a" 1 3]
                                             [2 "node_b" 4 2]
                                             [3 "node_c" 3 1]
                                             [4 "node_d" 2 3]
                                             [5 "node_e" 5 2]]]
      (sql/insert-record :certnames {:name node})
      (sql/insert-record :certname_facts_metadata {:certname node :timestamp (to-timestamp (-> facts-age days ago))})
      (sql/insert-record :catalogs {:id id :hash node :api_version 0 :catalog_version 0 :certname node :timestamp (to-timestamp (minus right-now (-> catalog-age days)))})))

  (doseq [version [:v2 :v3 :v4]]

    (testing "include total results count"
      (let [actual (:count (raw-retrieve-nodes version nil {:count? true}))]
        (is (= actual (count names)))))

    (testing "limit results"
      (doseq [[limit expected] [[1 1] [2 2] [100 5]]]
        (let [results (retrieve-node-names version nil {:limit limit})
              actual  (count results)]
          (is (= actual expected)))))

    (testing "order-by"
      (testing "rejects invalid fields"
        (is (thrown-with-msg?
              IllegalArgumentException #"Unrecognized column 'invalid-field' specified in :order-by"
              (retrieve-node-names version nil
                {:order-by [[:invalid-field :ascending]]}))))

      (testing "alphabetical fields"
        (doseq [[order expected] [[:ascending  ["node_a" "node_b" "node_c" "node_d" "node_e"]]
                                  [:descending ["node_e" "node_d" "node_c" "node_b" "node_a"]]]]
          (testing order
            (let [actual (retrieve-node-names version nil
                           {:order-by [[:name order]]})]
              (is (= actual expected))))))

      (testing "timestamp fields"
        (doseq [[order expected] [[:ascending  ["node_e" "node_b" "node_c" "node_d" "node_a"]]
                                  [:descending ["node_a" "node_d" "node_c" "node_b" "node_e"]]]]
          (testing order
            (let [actual (retrieve-node-names version nil
                           {:order-by [[:facts-timestamp order]]})]
              (is (= actual expected))))))

      (testing "multiple fields"
        (doseq [[[timestamp-order name-order] expected] [[[:ascending :descending] ["node_d" "node_a" "node_e" "node_b" "node_c"]]
                                                         [[:descending :ascending] ["node_c" "node_b" "node_e" "node_a" "node_d"]]]]
          (testing (format "catalog-timestamp %s name %s" timestamp-order name-order)
            (let [actual (retrieve-node-names version nil
                           {:order-by [[:catalog-timestamp timestamp-order]
                                       [:name name-order]]})]
              (is (= actual expected)))))))

    (testing "offset"
      (doseq [[order expected-sequences] [[:ascending  [[0 ["node_a" "node_b" "node_c" "node_d" "node_e"]]
                                                       [1 ["node_b" "node_c" "node_d" "node_e"]]
                                                       [2 ["node_c" "node_d" "node_e"]]
                                                       [3 ["node_d" "node_e"]]
                                                       [4 ["node_e"]]
                                                       [5 []]]]
                                          [:descending [[0 ["node_e" "node_d" "node_c" "node_b" "node_a"]]
                                                       [1 ["node_d" "node_c" "node_b" "node_a"]]
                                                       [2 ["node_c" "node_b" "node_a"]]
                                                       [3 ["node_b" "node_a"]]
                                                       [4 ["node_a"]]
                                                       [5 []]]]]]
        (testing order
          (doseq [[offset expected] expected-sequences]
            (let [actual (retrieve-node-names version nil
                           {:order-by [[:name order]] :offset offset})]
              (is (= actual expected)))))))))
