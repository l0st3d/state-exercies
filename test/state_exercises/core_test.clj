(ns state-exercises.core-test
  (:require [clojure.test :refer [deftest testing is are] :as t]
            [clojure.repl :refer [doc source]]
            [clojure.string :as string]
            [clojure.core.async :refer [>! <! <!! >!!] :as async]
            [clojure.spec.alpha :as s]
            [clojure.spec.gen.alpha :as gen]
            [clojure.edn :as edn])
  (:import [java.util Date]))

(deftest atoms
  (let [a (atom {})]
    (testing "basic updates and deref"
      (swap! a assoc :something 1)
      (is (= {:something 1} (deref a)))
      (is (= {:something 1} @a)))       ; shorthand for deref
    (testing "updates"
      (swap! a update :something inc)
      (is (= {:something 2} @a)))
    (testing "setting regardless of previous value"
      (reset! a {:something :else})
      (is (= {:something :else} @a)))))

(deftest refs
  (testing "transactions"
    (let [account-1          (ref {:balance 100.0M})
          account-2          (ref {:balance 700.0M})
          amount-to-transfer 10.0M]
      (dosync
       (alter account-1 update :balance - amount-to-transfer)
       (alter account-2 update :balance + amount-to-transfer)) ; whole transaction completes or fails
      (is (= 90.0M (:balance @account-1)))
      (is (= 710.0M (:balance @account-2))))))

(deftest agents
  (testing "asynchronous messages"
    (let [a (agent {:c 1})
          incr (fn [{:keys [c] :as a}]
                 (prn 'agent 'executed '>> c)
                 (assoc a :c (inc c)))]
      (dotimes [n 10]
        (send a incr))
      (prn 'messages 'sent)
      (await-for 10000 a)               ; wait for agent to process all functions
      (is (= 11 (:c @a)))))
  (testing "errors"
    (let [a (agent {:c 1})
          err (fn [a]
                (prn 'ERROR)
                (throw (ex-info "Error Processing" {:agent-state a :message "Error"})))]
      (send a err)
      (Thread/sleep 1000)
      (is (= "Error" (some-> a agent-error ex-data :message))) ; can get the error
      (is (thrown? Exception (send a err))) ; agent is in a broken state and won't accept new messages
      (restart-agent a {:c 0})              ; 'fix' the agent
      (is (= @a {:c 0}))))
  (testing "agents interaction with dosync blocks"
    (let [r (ref 0)
          a (agent :not-done-yet)]
      (dosync
       (ref-set r 1)
       (send a (constantly :done-now))  ; message not sent until the transaction commits
       (is (= :not-done-yet @a)))
      (await-for 1000 a)
      (is (= :done-now @a)))))

(def ^:dynamic *dynamic-var* ::global-state)

(defn dynamic-scope-1 []
  (prn 'dynamic-scope-1 '>> *dynamic-var*)
  *dynamic-var*)

(defn dynamic-scope-2 []
  (binding [*dynamic-var* ::dynamic-scope-2]
    (dynamic-scope-1)))

(deftest vars
  (testing "localised changes"
    (is (= ::global-state *dynamic-var*))
    (is (= ::global-state (deref #'*dynamic-var*)))
    (binding [*dynamic-var* ::local-state]
      (is (= ::local-state *dynamic-var*)))
    (testing "from a different thread"
      (-> (fn []
            (binding [*dynamic-var* ::thread-local-state] ; dynamic scope at work
              (Thread/sleep 100)
              (prn '> 'state 'on (Thread/currentThread) *dynamic-var*)
              (Thread/sleep 1000)
              (is (= ::thread-local-state *dynamic-var*))))
          Thread. .start)
      (prn '> 'state 'on (Thread/currentThread) *dynamic-var*)
      ;; wait for the thread to complete
      (Thread/sleep 2000)))
  (testing "be careful with dynamic scope"
    (is (= ::global-state (dynamic-scope-1)))
    (is (= ::dynamic-scope-2 (dynamic-scope-2)))))

(deftest validators
  (testing "atom validator"
    (let [a (atom {} :validator #(if-let [c (:c %)]
                                   (integer? c)
                                   true))]
      (swap! a assoc :c 0)
      (swap! a update :c inc)
      (is (= {:c 1} @a))
      (is (thrown? Exception (swap! a assoc :c :not-valid))))))

(defn agent-logger [k]
  (let [agent-logger     (agent nil)
        log-writer (fn [k r old new]
                     (println (second k) (Date.) new))]
    (add-watch agent-logger [::log-watcher k] log-writer)
    (fn [& messages]
      (send agent-logger (constantly (string/join " " messages)))
      nil)))

(deftest watchers
  (testing "agent watcher"
    (let [log (agent-logger ::test-agent-logger)]
      (is (nil? (log "message" "to" {:you "Rudy"}))))))

(def global-data (atom :test))

(deftest mocking
  (testing "with-redefs"
    (is (= :test @global-data))
    ;; with-redefs will temporarily redefine the contents of a var
    (with-redefs [global-data (atom nil)]
      (swap! global-data (constantly "fish"))
      (is (= "fish" @global-data)))
    (is (= :test @global-data))))

;; Core Async

(deftest go-blocks
  (testing "reading from a channel with a timeout"
    (let [log     (agent-logger :async-test)
          _       (log "starting go test")
          channel (async/chan)
          done-ch (async/go-loop []
                    (log "... waiting for input")
                    (let [timeout (async/timeout 1000)
                          result  (async/alt! channel ([x] (log "read" x) x)
                                              timeout (log "timed out"))]
                      (if result
                        (recur)
                        :finished)))]
      (async/go
        (dotimes [n 5]
          (log ">> about to write" n)
          (>! channel n)
          (log ">> wrote" n)))
      ;; >! and <! must be used inside a go block but <!! and >!! can be used in a normal Thread
      (is (= :finished (<!! done-ch))))))

(defn put-to-channel-up-to-n [channel n log]
  (async/go
    (dotimes [n n]
      (log ">> about to write" n)
      (>! channel n)
      (log ">> wrote" n))))

(defn take-from-channel-with-timeout
  ([channel]
   (take-from-channel-with-timeout channel prn))
  ([channel log]
   (async/go
     (let [timeout (async/timeout 1000)]
       (async/alt! channel ([x] (log "read" x) x)
                   timeout (log "timed out"))))))

(deftest go-block-with-buffers
  (testing "lossy buffers"
    (testing "dropping-buffer"
      (let [log          (agent-logger :async-test)
            _            (log "starting lossy buffers test")
            channel      (async/chan (async/dropping-buffer 1))
            done-putting (put-to-channel-up-to-n channel 5 log)
            _            (<!! done-putting)
            done-ch      (take-from-channel-with-timeout channel log)]
        (is (= 0 (<!! done-ch)))))

    (testing "sliding-buffer"
      (let [log          (agent-logger :async-test)
            _            (log "starting lossy buffers test")
            channel      (async/chan (async/sliding-buffer 1))
            done-putting (put-to-channel-up-to-n channel 5 log)
            _            (<!! done-putting)
            done-ch      (take-from-channel-with-timeout channel log)]
        (is (= 4 (<!! done-ch)))))))

(deftest pipeline
  (let [input-chan  (async/chan 100)
        output-chan (async/chan 100)]

    (comment
      ;; TODO use async/pipeline to transform the input strings
      ;; - split out the numbers
      ;; - filter out the odd numbers
      ;;
      ;; so for example, an input of

      ["5em6k" "" "v" "94lpy" "T" "rvh12112I0PTG"]

      ;; would be transformed into

      [6 94 12112 0]

      ;; transducers look like the sequence operations that we have
      ;; been using over the course, but are combined with `comp`
      ;; rather than `->>`. So where you would write:

      (->> (range 1000)
           (map inc)
           (filter even?)
           (take 10)
           (into []))

      ;; you now write;

      (into []
            (comp (map inc)
                  (filter even?)
                  (take 10))
            (range 1000))


      ;; remember that this:

      (->> (range 1000)
           (map inc)
           (filter even?)
           (take 10)
           (into []))

      ;; is the same as writing this:

      (into [] (take 10 (filter even? (map inc (range 1000)))))

      ;; Which you can prove to yourself by running this at the repl:
      
      (macroexpand '(->> (range 1000) (map inc) (filter even?) (take 10) (into [])))

      )

    
    ;; put 100 random strings onto input-chan
    (doseq [s (gen/sample (s/gen string?) 100)]
      (async/put! input-chan s))

    ;; take as many things off the output-chan as possible, check each
    ;; one to see if it's an even number. Also count them to make sure
    ;; there's more than one
    (loop [c 0]
      (let [timeout (async/timeout 100)
            x       (async/alt!! output-chan ([x] x)
                                 timeout     :timeout)]
        (if (= x :timeout)
          (is (> c 0))
          (do (is (and (number? x) (even? x)))
              (recur (inc c))))))))


(comment
  ;; start the repl - or use proto-repl
  lein repl

  ;; load the namespace
  (require '[state-exercises.core-test])

  ;; switch to that namespace
  (in-ns 'state-exercises.core-test)

  ;; run a function
  (go-block-with-buffers)

  ;; run all tests
  (t/run-tests)

  ;;;; Tasks
  ;;
  ;; - Read through the tests and try separate how each of the
  ;;   reference types operate. There are synchronous, co-ordinated,
  ;;   unco-ordinated, dynamyic and isolated changes going on. Try to
  ;;   apply labels to the different reference types.
  ;;
  ;; - put the classes data structure from week 3:

  [["9310" "Fundamentals of Programming"          "10:00" 1]
   ["521"  "Programming for Beginners"            "10:00" 1]
   ["9306" "Networking and Server Administration" "14:00" 2]
   ["310"  "Fundamentals of Programming"          "15:00" 1]]
  
  ;;   in an atom and write functions to
  ;;   - store add a new class
  ;;   - find all classes in a room
  ;;   - find all classes for a given course
  ;;   - how would you restructure the data to make it easier to
  ;;     answer these sorts of questions?
  ;;
  ;; - rewrite the logger to use channels instead of agents
  ;;
  ;; - use pipeline and a transducer to transform data and copy to
  ;;   another chan, i.e. implement the pipeline test. You can run
  ;;   only that test at the repl with:

  (t/test-vars [#'pipeline])
  
  )
