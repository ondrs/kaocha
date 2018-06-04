(ns kaocha.testable.var-test
  (:require [clojure.test :as t :refer :all]
            [kaocha.test-factories :as f]
            [kaocha.testable :as testable]
            [kaocha.testable.var]
            [kaocha.report :as report]
            [kaocha.classpath]
            [kaocha.test-helper]
            [kaocha.core-ext :refer :all]))

(def ^:dynamic *report-history* nil)

(defmacro with-test-ctx
  "When testing lower level functions, make sure the necessary shared state is set up."
  [opts & body]
  `(binding [t/*report-counters* (ref t/*initial-report-counters*)
             t/*testing-vars* (list)
             *report-history* (atom [])]
     (with-redefs [t/report (fn [m#]
                              (swap! *report-history* conj m#)
                              (report/report-counters m#)
                              (when (:fail-fast? ~opts) (report/fail-fast m#)))]
       (let [result# (do ~@body)]
         {:result result#
          :report @*report-history*}))))

(deftest run-test
  (testing "a passing test var"
    (kaocha.classpath/add-classpath "fixtures/a-tests")
    (require 'foo.bar-test)
    (let [{:keys [result report]}
          (with-test-ctx {:fail-fast? true}
            (testable/run {:kaocha.testable/type :kaocha.type/var
                           :kaocha.testable/id   :foo.bar-test/a-test,
                           :kaocha.var/name      'foo.bar-test/a-test
                           :kaocha.var/var       (resolve 'foo.bar-test/a-test)
                           :kaocha.var/test      (-> (resolve 'foo.bar-test/a-test) meta :test)}))]

      (is (match? {:kaocha.testable/type :kaocha.type/var
                   :kaocha.testable/id   :foo.bar-test/a-test
                   :kaocha.var/name      'foo.bar-test/a-test
                   :kaocha.var/var       (resolve 'foo.bar-test/a-test)
                   :kaocha.var/test      fn?
                   :kaocha.result/count  1
                   :kaocha.result/pass   1
                   :kaocha.result/error  0
                   :kaocha.result/fail   0}
                  result))

      (is (match? [{:type :begin-test-var, :var (resolve 'foo.bar-test/a-test)}
                   {:type :pass, :expected true, :actual true, :message nil}
                   {:type :end-test-var, :var (resolve 'foo.bar-test/a-test)}]
                  report))))

  (testing "a failing test var"
    (let [{:keys [result report]}
          (with-test-ctx {:fail-fast? true}
            (testable/run
              (f/var-testable {:kaocha.var/test (fn [] (is false))})))]

      (is (match? {:kaocha.result/count  1
                   :kaocha.result/pass   0
                   :kaocha.result/error  0
                   :kaocha.result/fail   1}
                  result))

      (is (match? [{:type :begin-test-var, :var var?}
                   {:type :fail, :expected false, :actual false, :message nil}
                   {:type :end-test-var, :var var?}]
                  report))))

  (testing "an erroring test var"
    (let [{:keys [result report]}
          (with-test-ctx {:fail-fast? true}
            (testable/run
              (f/var-testable {:kaocha.var/test (fn [] (throw (ex-info "ERROR!" {})))})))]

      (is (match? {:kaocha.result/count  1
                   :kaocha.result/pass   0
                   :kaocha.result/error  1
                   :kaocha.result/fail   0}
                  result))

      (is (match? [{:type :begin-test-var, :var var?}
                   {:type :error
                    :file string?
                    :line pos-int?
                    :expected nil
                    :actual exception?
                    :message "Uncaught exception, not in assertion."}
                   {:type :end-test-var, :var var?}]
                  report))))

  (testing "multiple assertions"
    (let [{:keys [result report]}
          (with-test-ctx {:fail-fast? false}
            (testable/run
              (f/var-testable {:kaocha.var/test (fn []
                                                  (is true)
                                                  (is true)
                                                  (is false)
                                                  (is true))})))]

      (is (match? {:kaocha.result/count  1
                   :kaocha.result/pass   3
                   :kaocha.result/error  0
                   :kaocha.result/fail   1}
                  result))

      (is (match? [{:type :begin-test-var, :var var?}
                   {:type :pass}
                   {:type :pass}
                   {:type :fail, :file string?, :line pos-int?}
                   {:type :pass}
                   {:type :end-test-var, :var var?}]
                  report))))

  (testing "early exit"
    (let [{:keys [result report]}
          (with-test-ctx {:fail-fast? true}
            (testable/run
              (f/var-testable {:kaocha.var/test (fn []
                                                  (is true)
                                                  (is true)
                                                  (is false)
                                                  (is true))})))]

      (is (match? {:kaocha.result/count  1
                   :kaocha.result/pass   2
                   :kaocha.result/error  0
                   :kaocha.result/fail   1}
                  result))

      (is (match? [{:type :begin-test-var, :var var?}
                   {:type :pass}
                   {:type :pass}
                   {:type :fail, :file string?, :line pos-int?}
                   {:type :end-test-var, :var var?}]
                  report)))

    (testing "early exit - exception"
      (let [{:keys [result report]}
            (with-test-ctx {:fail-fast? true}
              (testable/run
                (f/var-testable {:kaocha.var/test (fn []
                                                    (is true)
                                                    (is true)
                                                    (throw (Exception. "ERROR!"))
                                                    (is true))})))]

        (is (match? {:kaocha.result/count  1
                     :kaocha.result/pass   2
                     :kaocha.result/error  1
                     :kaocha.result/fail   0}
                    result))

        (is (match? [{:type :begin-test-var, :var var?}
                     {:type :pass}
                     {:type :pass}
                     {:type :error, :file string?, :line pos-int?}
                     {:type :end-test-var, :var var?}]
                    report))))))