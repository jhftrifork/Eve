(ns aurora.language
  (:require [clojure.set :refer [union intersection difference subset?]]
            [aurora.data :as data])
  (:require-macros [aurora.macros :refer [console-time set!! conj!! disj!! assoc!! apush apush* avec aclear]]
                   [aurora.language :refer [deffact]]))

(declare resolve)

(extend-protocol ISeqable
  TransientArrayMap
  (-seq [this]
        (if (.-editable? this)
          (persistent-array-map-seq (.-arr this) 0 nil)
          (throw (js/Error. "seq after persistent"))))
  TransientHashMap
  (-seq [this]
        (if (.-edit this)
          (when (pos? (.-count this))
            (let [s (if-not (nil? (.-root this)) (.inode-seq (.-root this)))]
              (if (.-has-nil? this)
                (cons [nil (.-nil-val this)] s)
                s)))
          (throw (js/Error. "seq after persistent"))))
  TransientHashSet
  (-seq [this]
        (keys (.-transient-map this))))

(defn arr== [arr-a arr-b]
  (and (== (alength arr-a) (alength arr-b))
       (loop [i 0]
         (if (>= i (alength arr-a))
           true
           (if (== (aget arr-a i) (aget arr-b i))
             (recur (+ i 1))
             false)))))

(defn arr= [arr-a arr-b]
  (and (== (alength arr-a) (alength arr-b))
       (loop [i 0]
         (if (>= i (alength arr-a))
           true
           (if (= (aget arr-a i) (aget arr-b i))
             (recur (+ i 1))
             false)))))

;; TODO facts and plans need to be serializable

;; FACTS

(deftype FactShape [id madlib keys]
  Object
  (toString [this]
            (apply str (interleave madlib (map (fn [k] (str "[" (name k) "]")) keys))))
  IEquiv
  (-equiv [this other]
          (and (instance? FactShape other)
               (= id (.-id other)))))

(defn fact-hash [values]
  (if (> (alength values) 0)
    (loop [result (hash (aget values 0))
           i 1]
      (if (< i (alength values))
        (recur (hash-combine result (hash (aget values i))) (+ i 1))
        result))
    0))

;; if given a shape behaves like a record, otherwise behaves like a vector
(deftype Fact [shape values ^:mutable __hash]
  Object
  (toString [this]
            (if (and shape (instance? FactShape shape))
              (apply str (interleave (.-madlib shape) (map (fn [k v] (str "[" (name k) " = " (pr-str v) "]")) (.-keys shape) values)))
              (apply str (when shape (str " " shape " ")) (map (fn [v] (str "[_ = " (pr-str v) "]")) values))))

  IEquiv
  (-equiv [this other]
          (and (instance? Fact other)
               (= shape (.-shape other))
               (arr== values (.-values other))))

  IHash
  (-hash [this]
         __hash)

  IIndexed
  (-nth [this n]
        (-nth this n nil))
  (-nth [this n not-found]
        (if (and (<= 0 n) (< n (alength values)))
          (aget values n)
          not-found))

  ILookup
  (-lookup [this k]
           (-lookup this k nil))
  (-lookup [this k not-found]
           (if (number? k)
             (-nth this k not-found)
             (when shape
               (loop [i 0]
                 (when (< i (alength (.-keys shape)))
                   (if (= k (aget (.-keys shape) i))
                     (aget values i)
                     (recur (+ i 1)))))))))

(defn fact-shape [id madlib&keys]
  (let [split-madlib&keys (clojure.string/split madlib&keys #"\[|\]")
        [madlib keys] [(take-nth 2 split-madlib&keys) (map keyword (take-nth 2 (rest split-madlib&keys)))]]
    (FactShape. id (into-array madlib) (into-array keys))))

(defn fact
  ([values]
   (assert (array? values) (pr-str values))
   (Fact. nil values (fact-hash values)))
  ([shape values]
   (assert (or (instance? FactShape shape) (keyword? shape) (string? shape)) (pr-str shape))
   (assert (array? values) (pr-str values))
   (if (instance? FactShape shape)
     (assert (= (alength values) (alength (.-keys shape))) (pr-str values shape)))
   (Fact. shape values (fact-hash values))))

(defn fact-ix [fact ix]
  (aget (.-values fact) ix))

(defn fact-ixes [fact ixes]
  (let [result #js []
        values (.-values fact)]
    (dotimes [i (count ixes)]
      (apush result (aget values (aget ixes i))))
    (Fact. nil result (fact-hash result))))

(defn fact-join-ixes [left-fact right-fact ixes]
  (let [result #js []
        left-values (.-values left-fact)
        right-values (.-values right-fact)]
    (dotimes [i (count ixes)]
      (let [ix (aget ixes i)]
        (if (< ix (alength left-values))
          (apush result (aget left-values ix))
          (apush result (aget right-values (- ix (alength left-values)))))))
    (Fact. nil result (fact-hash result))))

(comment
  (fact-shape ::eg "[a] has a [b] with a [c]")
  (fact-shape ::eg "The [a] has a [b] with a [c]")

  (fact #js [0 1 2])
  (fact (fact-shape ::eg "The [a] has a [b] with a [c]") #js [0 1 2])

  (deffact eg "[a] has a [b] with a [c]")
  (.-id eg)

  eg

  (.-id (.-shape (fact (fact-shape ::eg "[a] has a [b] with a [c]") #js ["a" "b" "c"])))

  (def x (->eg "a" "b" "c"))
  (nth x 1)
  (get x 1)
  (get x :b)

  (= eg (fact-shape ::eg "[a] has a [b] with a [c]"))

  (fact (fact-shape ::eg "[a] has a [b] with a [c]") #js ["a" "b" "c"])

  (= x x)
  (= x (fact (fact-shape ::eg "[a] has a [b] with a [c]") #js ["a" "b" "c"]))
  (= x (fact (resolve ::eg) #js ["a" "b" "c"]))
  (= x (fact eg #js ["a" "b" "c"]))
  (= x (->eg "a" "b" "c"))
  (= (Fact. nil #js ["a" "b" "c"]) (Fact. nil #js ["a" "b" "c"]))

  (= x (Fact. nil #js ["a" "b" "c"]))
  (= x (Fact. eg #js ["a" "b" 0]))
  (= x (fact (fact-shape ::foo "[a] has a [b] with a [c]") #js ["a" "b" "c"]))

  (fact-ixes x #js [2 1])
  )

;; FLOW STATE

(defrecord FlowState [node->state node->out-nodes node->facts node->update! node->stats trace plan])

(def trace? true)

(defn fixpoint! [{:keys [node->state node->out-nodes node->facts node->update! node->stats trace plan] :as flow-state}]
  (js/console.time "fixpoint!")
  (loop [node 0]
    (when (< node (alength node->facts))
      (let [in-facts (aget node->facts node)]
        (if (== 0 (alength in-facts))
          (recur (+ node 1))
          (let [out-facts #js []]
            (.call (aget node->update! node) nil node node->state node->stats in-facts out-facts)
            (aset node->facts node #js [])
            (when trace? (.push trace #js [node in-facts out-facts]))
            ;; (prn node in-facts out-facts (nth (:node->flow plan) node))
            (if (> (alength out-facts) 0)
              (let [out-nodes (aget node->out-nodes node)
                    min-out-node (areduce out-nodes i min-out-node (+ node 1)
                                          (let [out-node (aget out-nodes i)]
                                            (apush* (aget node->facts out-node) out-facts)
                                            (min out-node min-out-node)))]
                (recur min-out-node))
              (recur (+ node 1))))))))
  (js/console.timeEnd "fixpoint!")
  flow-state)

(defn filter-map-update! [fun]
  (fn [node node->state node->stats in-facts out-facts]
    (dotimes [i (alength in-facts)]
      (let [fact (aget in-facts i)]
        (when-let [new-fact (.call fun nil fact)]
          (apush out-facts new-fact))))))

(defn union-update! []
  (fn [node node->state node->stats in-facts out-facts]
    (let [facts (aget node->state node)]
      (dotimes [i (alength in-facts)]
        (let [fact (aget in-facts i)]
          (if (.assoc! facts fact true)
            (apush out-facts fact)
            (aset node->stats node "dupes" (+ (aget node->stats node "dupes") 1))))))))

(defn index-update! [key-ixes]
  (fn [node node->state node->stats in-facts out-facts]
    (let [index (aget node->state node)]
      (dotimes [i (alength in-facts)]
        (let [fact (aget in-facts i)
              key (fact-ixes fact key-ixes)
              facts (or (.lookup index key)
                        (do (let [facts (data/fact-map)]
                              (.assoc! index key facts)
                              facts)))]
          (if (.assoc! facts fact true)
            (apush out-facts fact)
            (aset node->stats node "dupes" (+ (aget node->stats node "dupes") 1))))))))

(defn lookup-update! [index-node key-ixes val-ixes]
  (fn [node node->state node->stats in-facts out-facts]
    (let [index (aget node->state index-node)]
      (dotimes [i (alength in-facts)]
        (let [left-fact (aget in-facts i)
              key (fact-ixes left-fact key-ixes)]
          (doseq [right-fact (keys (.lookup index key))]
              (apush out-facts (fact-join-ixes left-fact right-fact val-ixes))))))))

;; FLOWS

(defrecord Union [nodes])
(defrecord FilterMap [nodes fun&args])
(defrecord Index [nodes key-ixes])
(defrecord Lookup [nodes index-node key-ixes val-ixes])

(defn flow->nodes [flow]
  (:nodes flow))

;; FLOW PLAN

(defrecord FlowPlan [node->flow flow->node memory->shape->node kind->shape])

(defn flow-plan->flow-state [{:keys [node->flow] :as plan}]
  (let [node->state (make-array (count node->flow))
        node->out-nodes (make-array (count node->flow))
        node->facts (make-array (count node->flow))
        node->update! (make-array (count node->flow))
        node->stats (make-array (count node->flow))
        trace #js []]
    (dotimes [node (count node->flow)]
      (aset node->out-nodes node #js [])
      (aset node->facts node #js []))
    (dotimes [node (count node->flow)]
      (let [flow (nth node->flow node)]
        (aset node->state node
              (condp = (type flow)
                Union (data/fact-map)
                FilterMap nil
                Index (data/fact-map)
                Lookup nil))
        (doseq [in-node (:nodes flow)]
          (apush (aget node->out-nodes in-node) node))
        (aset node->update! node
              (condp = (type flow)
                Union (union-update!)
                FilterMap (filter-map-update! (apply (resolve (first (:fun&args flow))) (rest (:fun&args flow))))
                Index (index-update! (:key-ixes flow))
                Lookup (lookup-update! (:index-node flow) (:key-ixes flow) (:val-ixes flow))))
        (aset node->stats node
              (condp = (type flow)
                Union #js {:dupes 0}
                FilterMap nil
                Index #js {:dupes 0}
                Lookup nil))))
    (FlowState. node->state node->out-nodes node->facts node->update! node->stats trace plan)))

(defn empty-state-of [plan state]
  (if (= plan (:plan state))
    (let [node->flow (:node->flow plan)
          node->state (make-array (count node->flow))
          node->out-nodes (:node->out-nodes state)
          node->facts (make-array (count node->flow))
          node->update! (:node->update! state)
          node->stats (make-array (count node->flow))
          trace #js []]
      (dotimes [node (alength node->out-nodes)]
        (aset node->facts node #js [])
        (let [flow (nth node->flow node)]
          (aset node->state node
              (condp = (type flow)
                Union (data/fact-map)
                FilterMap nil
                Index (data/fact-map)
                Lookup nil))
          (aset node->stats node
              (condp = (type flow)
                Union #js {:dupes 0}
                FilterMap nil
                Index #js {:dupes 0}
                Lookup nil))))
      (FlowState. node->state node->out-nodes node->facts node->update! node->stats trace plan))
    (do (println "Rebuilding state!")
      (flow-plan->flow-state plan))))

(def empty-flow-plan
  (FlowPlan. [] {} {} {}))

(defn add-flow-without-memo [{:keys [node->flow flow->node] :as flow-plan} flow]
  (let [node (count node->flow)
        node->flow (conj node->flow flow)
        flow->node (assoc flow->node flow node)]
    [(assoc flow-plan :node->flow node->flow :flow->node flow->node) node]))

(defn add-flow [{:keys [node->flow flow->node] :as flow-plan} flow]
  (if-let [node (get flow->node flow)]
    [flow-plan node]
    (add-flow-without-memo flow-plan flow)))

(defn memory! [memory]
  (assert (#{:known|pretended :remembered :forgotten} memory) (pr-str memory)))

(defn add-memory [{:keys [node->flow memory->shape->node] :as flow-plan} memory shape]
  (memory! memory)
  (let [[flow-plan node] (add-flow-without-memo flow-plan (assoc (Union. #{}) :memory memory :shape shape))
        memory->shape->node (assoc-in memory->shape->node [memory shape] node)]
    (assoc flow-plan :memory->shape->node memory->shape->node)))

(defn add-shape [flow-plan kind shape]
  (case kind
    :pretended (-> flow-plan
                   (add-memory :known|pretended shape)
                   (update-in [:kind->shape kind] #(conj (or % #{}) shape)))
    :known (-> flow-plan
               (add-memory :known|pretended shape)
               (add-memory :remembered shape)
               (add-memory :forgotten shape)
               (update-in [:kind->shape kind] #(conj (or % #{}) shape)))))

(defn get-memory [{:keys [memory->shape->node] :as flow-plan} memory shape]
  (memory! memory)
  (or (get-in memory->shape->node [memory shape])
      (assert false (str "No node for " (pr-str memory) " " (pr-str shape) " in " (pr-str flow-plan)))))

(defn add-input [flow-plan output-node memory shape]
  (update-in flow-plan [:node->flow output-node :nodes] conj (get-memory flow-plan memory shape)))

(defn add-output [flow-plan input-node memory shape]
  (update-in flow-plan [:node->flow (get-memory flow-plan memory shape) :nodes] conj input-node))

;; IXES

(defn ix-of [vector value]
  (let [count (count vector)]
    (loop [ix 0]
      (if (< ix count)
        (if (= value (nth vector ix))
          ix
          (recur (+ ix 1)))
        (assert false (str (pr-str value) " is not contained in " (pr-str vector)))))))

(defn ixes-of [vector values]
  (into-array (map #(ix-of vector %) values)))

;; EXPRS

(defn expr->fun [vars expr]
  (apply js/Function (conj (vec vars) (str "return " expr ";"))))

(defn when->fun [vars expr]
  (let [when-fun (expr->fun vars expr)]
    (fn [fact]
      (let [values (.-values fact)]
        (when (.apply when-fun nil values)
          fact)))))

(defn let->fun [vars expr]
  (let [let-fun (expr->fun vars expr)]
    (fn [fact]
      (let [values (.-values fact)
            new-values (aclone values)
            new-value (.apply let-fun nil values)]
        (apush new-values new-value)
        (Fact. nil new-values (fact-hash new-values))))))

(defn when-let->fun [name-ix vars expr]
  (let [let-fun (expr->fun vars expr)]
    (fn [fact]
      (let [values (.-values fact)
            old-value (aget values name-ix)
            new-value (.apply let-fun nil values)]
        (when (= old-value new-value)
          fact)))))

;; PATTERNS

(defn pattern->vars [pattern]
  (vec (distinct (filter symbol? (.-values pattern)))))

(defn pattern->filter [pattern]
  `(pattern->filter* ~(.-shape pattern)))

(defn pattern->filter* [shape]
  (fn [fact]
    (when (= shape (.-shape fact))
      fact)))

(defn pattern->constructor [source-vars pattern]
  (let [shape (.-shape pattern)
        values (.-values pattern)
        source-ixes #js []
        sink-ixes #js []]
    (doseq [[value ix] (map vector (.-values pattern) (range))]
      (when (symbol? value)
        (do
          (apush source-ixes (ix-of source-vars value))
          (apush sink-ixes ix))))
    `(pattern->constructor* ~shape ~values ~source-ixes ~sink-ixes)))

(defn pattern->constructor* [shape values source-ixes sink-ixes]
  (fn [fact]
    (let [source (.-values fact)
          sink (aclone values)]
      (dotimes [i (alength source-ixes)]
        (aset sink (aget sink-ixes i) (aget source (aget source-ixes i))))
      (Fact. shape sink (fact-hash sink)))))

(defn pattern->deconstructor [pattern]
  (let [shape (.-shape pattern)
        seen? (atom {})
        constant-values #js []
        constant-ixes #js []
        var-ixes #js []
        dup-value-ixes #js []
        dup-var-ixes #js []]
    (doseq [[value ix] (map vector (.-values pattern) (range))]
      (if (symbol? value)
        (if-let [dup-value-ix (@seen? value)]
          (do
            (apush dup-value-ixes dup-value-ix)
            (apush dup-var-ixes ix))
          (do
            (apush var-ixes ix)
            (swap! seen? assoc value ix)))
        (do
          (apush constant-values value)
          (apush constant-ixes ix))))
    `(pattern->deconstructor* ~constant-values ~constant-ixes ~var-ixes ~dup-value-ixes ~dup-var-ixes)))

(defn pattern->deconstructor* [constant-values constant-ixes var-ixes dup-value-ixes dup-var-ixes]
  (fn [fact]
    (let [source (.-values fact)]
      (loop [i 0]
        (if (< i (alength constant-values))
          (when (= (aget constant-values i) (aget source (aget constant-ixes i)))
            (recur (+ i 1)))
          (loop [i 0]
            (if (< i (alength dup-value-ixes))
              (when (= (aget source (aget dup-value-ixes i)) (aget source (aget dup-var-ixes i)))
                (recur (+ i 1)))
              (let [sink (make-array (alength var-ixes))]
                (dotimes [i (alength var-ixes)]
                  (aset sink i (aget source (aget var-ixes i))))
                (Fact. nil sink (fact-hash sink))))))))))

;; CLAUSES

(defrecord Recall [memory pattern]) ;; memory is one of :known|pretended :remembered :forgotten
(defrecord Compute [pattern])
(defrecord Output [memory pattern]) ;; memory is one of :pretended :remembered :forgotten

;; horrible non-relational things
(deffact Let "Let [name] be the result of [vars] [expr]")
(deffact When "When [vars] [expr]")

;; if clause can be calculated somehow then return a new [plan node vars] pair that calculates it
;; otherwise return nil
(defn add-clause [plan nodes vars clause]
  (condp = (type clause)
    Recall (let [{:keys [memory pattern]} clause
                 [plan node] (add-flow plan (FilterMap. #{} (pattern->deconstructor pattern)))
                 plan (add-input plan node memory (.-shape pattern))]
             [plan [node] (pattern->vars pattern)])
    Compute (let [{:keys [pattern]} clause]
              (condp = (.-shape pattern)
                Let (when (every? (set vars) (:vars pattern))
                      (let [fun&args (if (contains? (set vars) (:name pattern))
                                       `(when-let->fun ~(ix-of vars (:name pattern)) ~vars ~(:expr pattern))
                                       `(let->fun ~vars ~(:expr pattern)))
                            [plan node] (add-flow plan (->FilterMap nodes fun&args))]
                        [plan [node] (conj vars (:name pattern))]))
                When (when (every? (set vars) (:vars pattern))
                       (let [[plan node] (add-flow plan (->FilterMap nodes `(when->fun ~vars ~(:expr pattern))))]
                         [plan [node] vars]))))
    Output (let [{:keys [memory pattern]} clause
                 _ (assert (every? (set vars) (pattern->vars pattern)))
                 [plan output-node] (add-flow plan (FilterMap. nodes (pattern->constructor vars pattern)))
                 output-memory (case memory
                                 :pretended :known|pretended
                                 :remembered :remembered
                                 :forgotten :forgotten)
                 plan (add-output plan output-node output-memory (.-shape pattern))]
             [plan nodes vars])))

;; RULES

(defrecord Rule [clauses])

;; Correctness: Each clause must appear at least once in the plan
;; Heuristic: Each Recall clause is used at most once in the plan
;; Heuristic: Each Filter/Let clause is used at most once per path in the plan

(defn add-computes [plan&nodes&vars computes]
  (let [plan&nodes&vars (atom plan&nodes&vars)
        computes-skipped #js []]
    (doseq [compute computes]
      (if-let [new-plan&nodes&vars (apply add-clause (conj @plan&nodes&vars compute))]
        (reset! plan&nodes&vars new-plan&nodes&vars)
        (apush computes-skipped compute)))
    (if (= (count computes) (count computes-skipped))
      @plan&nodes&vars
      (recur @plan&nodes&vars computes-skipped))))

(defn join-clauses [plan nodes-a vars-a nodes-b vars-b]
  (let [key-vars (intersection (set vars-a) (set vars-b))
        key-vars-a (sort-by #(ix-of vars-a %) key-vars)
        key-vars-b (sort-by #(ix-of vars-b %) key-vars)
        val-vars (union (set vars-a) (set vars-b))
        index-ixes-a (ixes-of vars-a key-vars-a)
        index-ixes-b (ixes-of vars-b key-vars-b)
        lookup-ixes-a (ixes-of vars-a key-vars-b)
        lookup-ixes-b (ixes-of vars-b key-vars-a)
        val-ixes-a (ixes-of (concat vars-a vars-b) val-vars)
        val-ixes-b (ixes-of (concat vars-b vars-a) val-vars)
        [plan index-a] (add-flow plan (->Index nodes-a index-ixes-a))
        index-b (inc (count (:node->flow plan))) ;; gross :(
        [plan lookup-a] (add-flow plan (->Lookup [index-a] index-b lookup-ixes-a val-ixes-a))
        [plan index-b'] (add-flow plan (->Index nodes-b index-ixes-b))
        _ (assert (= index-b index-b'))
        [plan lookup-b] (add-flow plan (->Lookup [index-b] index-a lookup-ixes-b val-ixes-b))]
    [plan [lookup-a lookup-b] (vec (distinct (concat vars-a vars-b)))]))

(defn add-rule [plan rule]
  (let [recalls (filter #(= Recall (type %)) (:clauses rule))
        computes (set (filter #(= Compute (type %)) (:clauses rule)))
        outputs (filter #(= Output (type %)) (:clauses rule))
        main-plan (atom plan)
        nodes&vars (for [recall recalls]
                     (let [[plan node vars] (add-computes (add-clause @main-plan nil nil recall) computes)]
                       (reset! main-plan plan)
                       [node vars]))
        [nodes vars] (reduce (fn [[nodes-a vars-a] [nodes-b vars-b]]
                               (let [computes-unapplied (difference computes
                                                                    (filter #(add-clause @main-plan nodes-a vars-a %) computes)
                                                                    (filter #(add-clause @main-plan nodes-b vars-b %) computes))
                                     [plan nodes vars] (add-computes
                                                        (join-clauses @main-plan nodes-a vars-a nodes-b vars-b)
                                                        computes-unapplied)]
                                 (reset! main-plan plan)
                                 [nodes vars]))
                             nodes&vars)
        computes-unapplied (filter #(not (add-clause @main-plan nodes vars %)) computes)]
    (assert (empty? computes-unapplied) (str "Could not apply " (pr-str computes-unapplied) " to " (pr-str vars)))
    (doseq [output outputs]
      (let [[plan node vars] (add-clause @main-plan nodes vars output)]
        (reset! main-plan plan)))
    @main-plan))

(defn add-rules [plan rules]
  ;; TODO stratify
  (reduce add-rule plan rules))

;; TIME AND CHANGE

(defn add-facts [state memory shape facts]
  (memory! memory)
  (let [node (get-memory (:plan state) memory shape)
        arr (aget (:node->facts state) node)]
    (doseq [fact facts]
      (apush arr fact))
    state))

(defn get-facts [state memory shape]
  (memory! memory)
  (let [node (get-memory (:plan state) memory shape)]
    (into-array (keys (aget (:node->state state) node)))))

;; TODO make this incremental
(defn tick
  ([plan] (tick plan (flow-plan->flow-state plan)))
  ([plan state]
   (js/console.time "plan->state")
   (let [new-state (empty-state-of plan state)]
     (js/console.timeEnd "plan->state")
     (js/console.time "tick")
     (doseq [shape (get-in state [:plan :kind->shape :known])] ;; using old plan here
       (let [known (aget (:node->state state) (get-memory (:plan state) :known|pretended shape))
             remembered (aget (:node->state state) (get-memory (:plan state) :remembered shape))
             forgotten (aget (:node->state state) (get-memory (:plan state) :forgotten shape))
             new-known (aget (:node->facts new-state) (get-memory (:plan new-state) :known|pretended shape))]
         (doseq [fact (keys known)]
           (when (or (not (.lookup forgotten fact)) (.lookup remembered fact))
             (apush new-known fact)))
         (doseq [fact (keys remembered)]
           (when (and (not (.lookup known fact)) (not (.lookup forgotten fact)))
             (apush new-known fact)))))
     (js/console.timeEnd "tick")
     new-state)))

(defn tick&fixpoint [plan state]
  (fixpoint! (tick plan state)))

(defn clauses->rule [clauses]
  (Rule. clauses))

;; RESOLVE

(def symbol->fun
  #js {"let->fun" let->fun
       "when->fun" when->fun
       "when-let->fun" when-let->fun
       "pattern->filter*" pattern->filter*
       "pattern->constructor*" pattern->constructor*
       "pattern->deconstructor*" pattern->deconstructor*})

(defn resolve [symbol]
  (let [fun (aget symbol->fun (name symbol))]
    (assert fun (str "Could not find " symbol))
    fun))

;; TEMPORARY SHIMS

(defn add-facts-compat [state memory facts]
  (memory! memory)
  (doseq [fact facts]
    (let [shape (.-shape fact)
          node (get-memory (:plan state) memory shape)
          arr (aget (:node->facts state) node)]
      (apush arr fact)))
  state)

(defn get-facts-compat [state memory]
  (memory! memory)
  (let [facts #js []]
    (doseq [[shape node] (get-in state [:plan :memory->shape->node memory])
            fact (get-facts state memory shape)]
      (apush facts fact))
    facts))

(defn shapes&kinds&rules->plan [shapes&kinds rules]
  (add-rules (reduce (fn [plan [shape kind]] (add-shape plan kind shape)) empty-flow-plan shapes&kinds) rules))

;; assume state is :known unless it is used in pretend or is overridden in stdlib
(defn rules->shapes&kinds [rules facts]
  (let [shape->kind (atom {})]
    (doseq [rule rules
            clause (:clauses rule)]
      (when (= Recall (type clause))
        (swap! shape->kind assoc (.-shape (:pattern clause)) :known)))
    (doseq [rule rules
            clause (:clauses rule)]
      (when (= Output (type clause))
        (if (= :pretended (:memory clause))
          (swap! shape->kind assoc (.-shape (:pattern clause)) :pretended)
          (swap! shape->kind assoc (.-shape (:pattern clause)) :known))))
    (doseq [fact facts]
      (swap! shape->kind assoc (.-shape fact) :known))
    (doseq [[shape v] aurora.runtime.stdlib/madlibs]
      (if-not (:remembered v)
        (swap! shape->kind assoc shape :pretended)
        (swap! shape->kind assoc shape :known)))
    @shape->kind))

(defn rules->plan [rules facts]
  (shapes&kinds&rules->plan (rules->shapes&kinds rules facts) rules))

(defn unchanged? [old-state new-state]
  (js/console.time "unchanged?")
  (let [unchanged? (and (= (:plan old-state) (:plan new-state))
                        (every?
                         (fn [shape]
                           (arr= (get-facts old-state :known|pretended shape) (get-facts new-state :known|pretended shape)))
                         (get-in old-state [:plan :kind->shape :known])))]
    (js/console.timeEnd "unchanged?")
    unchanged?))

;; TESTS

(comment
  (deffact edge "[x] has an edge to [y]")
  (deffact connected "[x] is connected to [y]")

  (let [plan (-> empty-flow-plan
                 (add-shape :known edge)
                 (add-shape :pretended connected)
                 (add-rules [(Rule. [(Recall. :known|pretended (->edge 'x 'y))
                                     (Output. :pretended (->connected 'x 'y))])
                             (Rule. [(Recall. :known|pretended (->edge 'x 'y))
                                     (Recall. :known|pretended (->connected 'y 'z))
                                     (Output. :pretended (->connected 'x 'z))])]))
        state (flow-plan->flow-state plan)]
    (add-facts state :known|pretended edge #js [(->edge 0 1) (->edge 1 2) (->edge 2 3) (->edge 3 1)])
    (time (fixpoint! state))
    (get-facts state :known|pretended connected))

  (enable-console-print!)

  (let [plan (-> empty-flow-plan
                 (add-shape :known edge)
                 (add-shape :pretended connected)
                 (add-rules [(Rule. [(Recall. :known|pretended (->edge 'x 'y))
                                     (Output. :pretended (->connected 'x 'y))])
                             (Rule. [(Recall. :known|pretended (->edge 'x 'y))
                                     (Recall. :known|pretended (->connected 'y 'z))
                                     (Output. :pretended (->connected 'x 'z))])]))
        state (flow-plan->flow-state plan)]
    (add-facts state :known|pretended edge (into-array (for [i (range 100)] (->edge i (inc i)))))
    (js/console.time "new")
    (fixpoint! state)
    (js/console.timeEnd "new")
    (get-facts state :known|pretended connected))
  ;; 5 => 1 ms
  ;; 10 => 8 ms
  ;; 50 => 1093 ms
  ;; 100 => 11492 ms

  (let [plan (-> empty-flow-plan
                 (add-shape :known edge)
                 (add-shape :pretended connected)
                 (add-rules [(Rule. [(Recall. :known|pretended (->edge 'x 'y))
                                     (Compute. (->Let 'z '[x y] "x + y"))
                                     (Output. :pretended (->connected 'z 'z))])]))
        state (flow-plan->flow-state plan)]
    (add-facts state :known|pretended edge (into-array (for [i (range 100)]
                                                         (->edge i (inc i)))))
    (fixpoint! state)
    (get-facts state :known|pretended connected))
  )

(comment
  (deffact edge "[x] has an edge to [y]")
  (deffact connected "[x] is connected to [y]")

  (let [plan (-> empty-flow-plan
                 (add-shape :known edge)
                 (add-shape :known connected)
                 (add-rules
                  [(Rule. [(Recall. :known|pretended (->edge 'x 'y))
                           (Output. :remembered (->connected 'x 'y))])
                   (Rule. [(Recall. :known|pretended (->edge 'x 'y))
                           (Recall. :known|pretended (->connected 'y 'z))
                           (Output. :remembered (->connected 'x 'z))])]))
        state-0 (flow-plan->flow-state plan)]
    (add-facts state-0 :known|pretended edge #js [(->edge 0 1) (->edge 1 2) (->edge 2 3) (->edge 3 1)])
  (fixpoint! state-0)
  (for [state (take 5 (iterate #(tick&fixpoint plan %) state-0))]
    (count (get-facts state :known|pretended connected))))
  )

(comment

  (let [u (transient {})]
    (time
     (dotimes [i 1000000]
       (let [fact (fact #js [(mod i 1000) (mod i 100) (mod i 10)])]
         (assoc!! u fact fact)))))

  )
