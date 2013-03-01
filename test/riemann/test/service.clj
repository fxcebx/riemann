(ns riemann.test.service
  (:import (java.util.concurrent TimeUnit
                                 AbstractExecutorService
                                 Executor
                                 ExecutorService
                                 RejectedExecutionException
                                 LinkedBlockingQueue
                                 ArrayBlockingQueue
                                 SynchronousQueue
                                 ThreadPoolExecutor))
  (:require [riemann.logging :as logging])
  (:use riemann.service
        clojure.test))

(deftest thread-service-equiv-test
         (is (equiv? (thread-service :foo #())
                     (thread-service :foo #())))
         (is (equiv? (thread-service :test 1 #())
                     (thread-service :test 1 #())))
        
         (is (not (equiv? (thread-service :foo #())
                          (thread-service :bar #()))))
         (is (not (equiv? (thread-service :foo 1 #())
                          (thread-service :bar 1 #()))))
         (is (not (equiv? (thread-service :foo 1 #())
                          (thread-service :foo 2 #())))))

; THIS IS A MUTABLE STATE OF AFFAIRS
; WHICH IS TO SAY, IT IS FUCKING DREADFUL
(deftest thread-service-test
         (let [in (LinkedBlockingQueue.)
               out (LinkedBlockingQueue.)
               s   (thread-service
                     :test
                     (fn [core]
                       (.put out [(.take in) core])))
               send (fn [msg]
                      (.put in msg)
                      (.take out))]

           ; Shouldn't do anything before started.
           (.put in :before-start)
           (Thread/sleep 50)
           (is (= :before-start (.peek in)))
           (is (nil? (.peek out)))

           ; Should run when started
           (start! s)
           (is (= [:before-start nil] (.take out)))

           ; Should respond to subsequent messages
           (is (= [:a nil] (send :a)))

           ; Should reload core
           (reload! s :core)
           ; We may or may not have a waiting iteration with nil core
           (is (= :reload-1 (first (send :reload-1))))
           (is (= [:reload-2 :core] (send :reload-2)))
           
           ; Start! is idempotent
           ; Not a very good test--should probably check the threads used. :/
           (start! s)
           (is (= [:start-2 :core] (send :start-2)))

           ; Should shut down cleanly
           (let [f (future 
                     (Thread/sleep 50) 
                     (.put in :stop))]
             (stop! s)
             @f)
           (is #{nil [:stop :core]} (.poll out))

           ; Is stopped
           (.put in :stop-2)
           (Thread/sleep 50)
           (is #{:stop :stop-2} (.poll in))

           ; Stop is idempotent
           (stop! s)

           ; Can restart
           (.clear in)
           (.clear out)
           (reload! s :core-2)
           (start! s)
           (is (= [:restarted :core-2] (send :restarted)))))

(deftest literal-executor-service-equiv-test
         (are [f] (equiv? (f) (f))
              #(literal-executor-service
                 :cat
                 (ThreadPoolExecutor. 1 1 20 TimeUnit/SECONDS 
                                      (ArrayBlockingQueue. 10)))

              #(literal-executor-service
                 :mouse
                 (ThreadPoolExecutor. 1 10 20 TimeUnit/MILLISECONDS
                                      (LinkedBlockingQueue.))))

   (are [a b] (not (equiv? a b))
        (literal-executor-service
          :cat
          (ThreadPoolExecutor. 1 1 20 TimeUnit/SECONDS (ArrayBlockingQueue. 1)))
        (literal-executor-service
          :dog
          (ThreadPoolExecutor. 1 1 20 TimeUnit/SECONDS (ArrayBlockingQueue. 1)))
       
        (literal-executor-service
          :cat
          (ThreadPoolExecutor. 1 1 20 TimeUnit/SECONDS (ArrayBlockingQueue. 1)))
        (literal-executor-service
          :cat
          (ThreadPoolExecutor. 1 2 20 TimeUnit/SECONDS (ArrayBlockingQueue. 1)))
        
        (literal-executor-service
          :cat
          (ThreadPoolExecutor. 1 1 20 TimeUnit/SECONDS (ArrayBlockingQueue. 1)))
        (literal-executor-service
          :cat
          (ThreadPoolExecutor. 1 1 20 TimeUnit/SECONDS (ArrayBlockingQueue. 2)))))

(deftest threadpool-service-test
         (let [s (threadpool-service :cat {:queue-size 2})
               x (atom 0)
               run (fn []
                     (let [p (promise)]
                       (.execute s #(deliver p (swap! x inc)))
                       @p))]
           (is (thrown? RejectedExecutionException (run)))
           (is (= 0 @x))

           (logging/suppress "riemann.service" (.start! s))
           (is (= 1 (run)))
           (is (= 2 (run)))

           (logging/suppress "riemann.service" (.stop! s))
           (is (thrown? RejectedExecutionException (run)))
           (is (= 2 @x))))
