(ns rehook.test.browser
  (:require [rehook.core :as rehook]
            [rehook.dom :refer-macros [defui ui]]
            [rehook.dom.browser :as dom.browser]
            [rehook.test :as rehook.test]
            [rehook.util :as util]
            [zprint.core :as zp]
            [clojure.data :as data]
            ["react-highlight" :default Highlight]
            ["react-frame-component" :default Frame]
            ["react-error-boundary" :default ErrorBoundary]
            ["react-dom" :as react-dom]))

(goog-define HTML "")
(goog-define target "app")
(goog-define domheight 400)
(goog-define syntaxcss "https://cdnjs.cloudflare.com/ajax/libs/highlight.js/9.15.10/styles/github.min.css")

(defn zpr-str
  "Like pr-str, but using zprint"
  ([code]
   (zpr-str code 80))
  ([code numeric-width]
   (with-out-str
    (zp/zprint code numeric-width)))
  ([code numeric-width opts]
   (with-out-str
    (zp/zprint code numeric-width opts))))

(defn children [props]
  (some-> props util/react-props (aget "children")))

(defui clojure-highlight [_ props $]
  (apply $ Highlight {:language "clojure"} (children props)))

(defui js-highlight [_ props $]
  (apply $ Highlight {:language "javascript"} (children props)))

(defn current-scene [scenes index]
  (get-in scenes [:timeline index]))

(defn previous-scene [scenes index]
  (let [prev-index (dec index)]
    (when-not (neg? prev-index)
      (current-scene scenes prev-index))))

(defui error-handler [{:keys [title]} props]
  (let [error      (some-> props util/react-props (aget "error"))
        stacktrace (some-> props util/react-props (aget "componentStack"))]
    [:div {}
     [:h1 {} (str title)]
     [clojure-highlight {} (zpr-str error 120)]
     [js-highlight {} (str stacktrace)]]))

(defui material-icon [_ {:keys [icon]}]
  [:i {:className "material-icons"
       :style     {:userSelect "none"}}
   icon])

(defui code [{:keys [test-results]} {:keys [path]} $]
  (let [[idx1 idx2] path
        [scenes _]  (rehook/use-atom-path test-results [idx1 :scenes])
        scene       (current-scene scenes idx2)]
    ($ ErrorBoundary
       {:FallbackComponent (error-handler {:title "Error rendering Hiccup."} $)}
       ($ :div {:style {:overflow "scroll"}}
          ($ clojure-highlight {}
             (zpr-str (js->clj (:render scene)) 80))))))

(defui diff [{:keys [test-results]} {:keys [path]} $]
  (let [[idx1 idx2] path
        [scenes _]  (rehook/use-atom-path test-results [idx1 :scenes])
        scene       (current-scene scenes idx2)
        prev-scene  (previous-scene scenes idx2)]
    ($ clojure-highlight {}
       (zpr-str (data/diff (:render prev-scene)
                           (:render scene))
                80))))

(defui dom [{:keys [test-results]} {:keys [path]} $]
  (let [[idx1 idx2] path
        [scenes _]  (rehook/use-atom-path test-results [idx1 :scenes])
        scene       (current-scene scenes idx2)
        render      (:render scene)]
    ($ ErrorBoundary
       {:FallbackComponent (error-handler {:title "Error rendering to the DOM."} $)}
       ($ Frame {:initialContent HTML
                 :style          {:height (str domheight "px")
                                  :width  "100%"}}
          ;; bootstrap iframe with 'sandboxed' ctx
          (dom.browser/bootstrap
           {} identity clj->js
           (ui [_ _]
             render))))))

(defui state [{:keys [test-results]} {:keys [path]}]
  (let [[idx1 idx2] path
        [scenes _]  (rehook/use-atom-path test-results [idx1 :scenes])
        scene       (current-scene scenes idx2)
        prev-scene  (previous-scene scenes idx2)
        state       (some-> scene :state deref)
        prev-state  (some-> prev-scene :state deref)]
    (if state
      [:div {:style {:overflowX "auto"}}
       [:table {}
        [:thead {}
         [:tr {}
          [:th {} "component"]
          [:th {} "parent"]
          [:th {} "index"]
          [:th {} "previous value"]
          [:th {} "current value"]]]
        (into [:tbody {}]
              (map (fn [[[k i :as id] {:keys [current-value]}]]
                     [:tr {}
                      [:td {} (last k)]
                      [:td {} (or (-> k butlast last) "-")]
                      [:td {} (dec i)]
                      [:td {}
                       [clojure-highlight {}
                        (zpr-str (get-in prev-state [id :current-value]) 120)]]
                      [:td {}
                       [clojure-highlight {} (zpr-str current-value 120)]]])
                   state))]]

      [:div {} "No state mounted"])))

(defui effects [{:keys [test-results]} {:keys [path]}]
  (let [[idx1 idx2]  path
        [scenes _]   (rehook/use-atom-path test-results [idx1 :scenes])
        scene        (current-scene scenes idx2)
        effects      (some-> scene :effects deref)
        prev-scene   (previous-scene scenes idx2)
        prev-effects (some-> prev-scene :effects deref)]
    (if effects
      [:div {:style {:overflowX "auto"}}
       [:table {}
        [:thead {}
         [:tr {}
          [:th {} "component"]
          [:th {} "parent"]
          [:th {} "index"]
          [:th {} "prev deps"]
          [:th {} "deps"]
          [:th {} "evaled?"]]]
        (into [:tbody {}]
              (map (fn [[[k i :as id] {:keys [deps]}]]
                     (let [prev-deps (get-in prev-effects [id :deps])]
                       [:tr {}
                        [:td {} (last k)]
                        [:td {} (or (-> k butlast last) "-")]
                        [:td {} (dec i)]
                        [:td {} (pr-str prev-deps)]
                        [:td {} (pr-str deps)]
                        [:td {} (pr-str (rehook.test/eval-effect? idx2 prev-deps deps))]]))
                   effects))]]

      [:div {} "No effects mounted"])))

(defui button [_ {:keys [onClick selected title]}]
  [:div {:style   {:padding      "10px"
                   :border       (if selected
                                   "1px solid #222"
                                   "1px solid #ccc")
                   :borderRadius "3px"
                   :marginRight  "10px"
                   :cursor       "pointer"
                   :userSelect   "none"}
         :onClick onClick}
   (if selected
     [:strong {} (str title)]
     [:span {} (str title)])])

(defui test-assertion [{:keys [test-results]} {:keys [path debug]}]
  (let [[idx1 idx2]                      path
        debug?                           debug
        path                             [idx1 :tests idx2]
        [test _]                         (rehook/use-atom-path test-results path)
        [show-details? set-show-details] (rehook/use-state true)
        [tab set-tab]                    (rehook/use-state :dom)]

    (rehook/use-effect
     (fn []
       (when debug?
         (set-show-details true))
       (constantly nil))
     [(name tab)])

    [:div {:style {}}
     [:div {:style {:display         "flex"
                    :border          "1px solid #88CC88"
                    :padding         "10px"
                    :borderRadius    "3px"
                    :color           "#F8F8F8"
                    :justifyContent  "space-between"
                    :alignItems      "center"
                    :flexWrap        "wrap"
                    :marginTop       "20px"
                    :backgroundColor (if (:pass test)
                                       "#77DD77"
                                       "#B74747")}}

      [:div {:style {:width      "50px"
                     :height     "100%"
                     :alignItems "left"}}
       [material-icon
        {:icon (if (:pass test) "done" "highlight_off")}]]

      [:div {}
       [:strong {:style {:textShadow "0px 0.2px #222"
                       :color "#F9F9F9"}}
        (:title test)]
       [clojure-highlight {} (zpr-str (:form test) 80)]]

      [:div {:style {:border          "1px solid #ccc"
                     :padding         "20px"
                     :backgroundColor "#ccc"
                     :fontSize        "24px"
                     :textAlign       "center"
                     :userSelect      "none"
                     :width           "70px"}}
       (:scene test)]]

     (when debug?
       [:div {:style {:display        "flex"
                      :justifyContent "space-between"
                      :alignItems     "center"
                      :flexWrap       "wrap"}}

        [:div {:style {:display      "flex"
                       :borderRadius "3px"
                       :alignItems   "center"
                       :flexWrap     "wrap"
                       :marginTop    "10px"
                       :marginBottom "10px"}}

         [button {:onClick  #(set-tab :dom)
                  :title    "DOM"
                  :selected (= :dom tab)}]

         [button {:onClick  #(set-tab :hiccup)
                  :title    "Hiccup"
                  :selected (= :hiccup tab)}]

         (when (pos? (:scene test))
           [button {:onClick  #(set-tab :diff)
                    :selected (= tab :diff)
                    :title    "Diff"}])

         [button {:onClick  #(set-tab :effects)
                  :selected (= tab :effects)
                  :title    "Effects"}]

         [button {:onClick  #(set-tab :state)
                  :selected (= tab :state)
                  :title    "State"}]]

        [:div {:onClick #(set-show-details (not show-details?))
               :style   {:color      "blue"
                         :cursor     "pointer"
                         :userSelect "none"}}
         (if show-details? "Hide" "Show")]])

     (when (and show-details? debug?)
       (case tab
         :dom     [dom {:path [idx1 (:scene test)]}]
         :hiccup  [code {:path [idx1 (:scene test)]}]
         :diff    [diff {:path [idx1 (:scene test)]}]
         :effects [effects {:path [idx1 (:scene test)]}]
         :state   [state {:path [idx1 (:scene test)]}]))]))

(defui mutation [{:keys [test-results]} {:keys [path]}]
  (let [[idx1 idx2] path
        path        [idx1 :tests idx2]
        [test _]    (rehook/use-atom-path test-results path)]
    [:div {:style {:display         "flex"
                   :marginTop       "20px"
                   :border          "1px solid #ccc"
                   :padding         "10px"
                   :borderRadius    "3px"
                   :color           "#444"
                   :justifyContent  "space-between"
                   :alignItems      "center"
                   :flexWrap        "wrap"
                   :backgroundColor "#FCFCFC"}}

     [:div {:style {:width      "50px"
                    :height     "100%"
                    :alignItems "left"}}
      [material-icon {:icon "send"}]]

     [:div {:style {:fontWeight "1000"}}
      (:title test)
      [clojure-highlight {} (zpr-str (:form test) 80)]]

     [:div {:style {:border          "1px solid #ccc"
                    :padding         "20px"
                    :backgroundColor "#ccc"
                    :fontSize        "24px"
                    :textAlign       "center"
                    :userSelect      "none"
                    :width           "70px"}}
      (:scene test)
      [material-icon {:icon "trending_flat"}]
      (inc (:scene test))]]))

(defn build-summary-component [name ns test-index summary-index [test & tests]]
  (case (:type test)
    :assertion
    (let [[next-assertion & _] (filter #(= :assertion (:type %)) tests)]
      [test-assertion {:path  [test-index summary-index]
                       :key   (str ns "/" name "/" "assertion:"  summary-index)
                       :debug (not= (:scene next-assertion) (:scene test))}])

    :mutation
    [mutation {:path [test-index summary-index]
               :key  (str ns "/" name "/" "mutation:" summary-index)}]))

(defui summary [{:keys [test-results]} props]
  (let [test-index (:index props)
        [{:keys [name ns tests]} _] (rehook/use-atom-path test-results [test-index])]
    (into [:div {}]
          ;; TODO: not use loop
          (loop [summary-index 0
                 tests tests
                 components []]
            (if (empty? tests)
              components
              (let [component (build-summary-component name ns test-index summary-index tests)]
                (recur (inc summary-index) (rest tests) (conj components component))))))))

(defui test-error [{:keys [test-results]} {:keys [index]}]
  (let [[e _]     (rehook/use-atom-path test-results [index :e])
        [stack _] (rehook/use-atom-path test-results [index :stack])]
    [:div {}
     [:h2 {} "Error evaluating test!"]
     (if stack
       [js-highlight {} (str stack)]
       [js-highlight {} (str e)])]))

(defui testcard [{:keys [test-results]} {:keys [index]}]
  (let [[{:keys [name form ns line tests error?]} _] (rehook/use-atom-path test-results [index])
        test-str (zpr-str (first form) 80)
        assertions (filter #(= :assertion (:type %)) tests)
        pass? (and (every? :pass assertions) (not error?))
        [show-code-snippet? set-show-code-snippet] (rehook/use-state false)
        [expanded? set-expanded] (rehook/use-state (not pass?))
        total-assertions (count assertions)
        title (str ns "/" name
                   (if error?
                     " (1 error)"
                     (str " (" total-assertions " "
                          (case total-assertions 1 "assertion" "assertions") ")")))]

    [:div {:style {:border       "1px solid"
                   :borderRadius "3px"
                   :borderLeft   "15px solid"
                   :display      "flex"
                   :borderColor  (if pass?
                                   "#77DD77"
                                   "#B74747")
                   :paddingLeft "15px"
                   :paddingRight "15px"
                   :flexDirection "column"}}

     [:div {:style {:cursor "pointer"
                    :flexGrow "1"}
            :onClick #(set-expanded (not expanded?))}

      [:h2 {:style {:color "#222"}}
       [:span {:style {:marginRight "5px"}}
        [material-icon {:icon (if expanded?
                                "keyboard_arrow_down"
                                "chevron_right")}]]
       title]]

     (when expanded?
       [:div {}
        (when-not error?
          [:div {:onClick #(set-show-code-snippet (not show-code-snippet?))
                 :style   {:color      "blue"
                           :cursor     "pointer"
                           :userSelect "none"}}
           (if show-code-snippet? "Hide test form" "Show test form")])

        (when (and show-code-snippet? (not error?))
          [clojure-highlight {} test-str])

        (if error?
          [test-error {:index index}]
          [summary {:index index}])])]))

(defn test-stats [test-results]
  (let [tests      (mapcat :tests test-results)
        assertions (filter #(= :assertion (:type %)) tests)
        errors     (filter :error? test-results)]
    {:total-tests      (count test-results)
     :total-assertions (count assertions)
     :total-errors     (count errors)
     :pass             (count (filter :pass assertions))
     :fail             (count (filter (comp not :pass) assertions))}))

(defn test-outcome-str
  [{:keys [total-tests total-assertions fail total-errors]}]
  (let [test-str      (if (= 1 total-tests) "test" "tests")
        assertion-str (if (= 1 total-assertions) "assertion" "assertions")
        fail-str      (if (= 1 fail) "failure" "failures")
        errors-str    (if (= 1 total-errors) "error" "errors")]
    (str total-tests " " test-str ", "
         total-assertions " " assertion-str ", "
         total-errors " " errors-str ", "
         fail " " fail-str ".")))

(defui report-summary [{:keys [test-results]} _]
  (let [[test-results _] (rehook/use-atom test-results)
        test-stats       (test-stats test-results)
        output           (test-outcome-str test-stats)
        success?         (and (zero? (:fail test-stats))
                              (zero? (:total-errors test-stats)))]

    (into [:div {}
           [:div {:style {:color (if success?
                                   "green"
                                   "red")}}
            output]]
          (map-indexed
           (fn [i {:keys [name ns]}]
             [:div {:style {:marginTop "30px"}
                    :key   (str "test-summary-" ns "/" name)}
              [testcard {:key   (str "test-summary-" ns "/" name "/testcard")
                         :index i}]])
           test-results))))

(defn run-test!
  [{:keys [test column line end-line end-column ns name]}]
  (let [result (try (test)
                    (catch js/Error e
                      {:error? true
                       :e      e
                       :stack  (aget e "stack")
                       :name   name}))]
    (assoc result
      :column     column
      :line       line
      :end-line   end-line
      :end-column end-column
      :ns         ns)))

(defn run-tests!
  [curr-registry test-results]
  (->> curr-registry
       (mapv (fn [[_ test-meta]]
               (run-test! test-meta)))
       (sort-by #(str (:ns %) "-" (:name %)))
       (vec)
       (reset! test-results)))

(defui rehook-summary [{:keys [registry test-results]} _]
  (let [[registry _] (rehook/use-atom registry)]

    ;; Re-run our tests everytime the registry updates.
    (rehook/use-effect
     (fn []
       (js/console.log "%c running rehook.test report ~~~ ♪┏(・o･)┛♪"
                       "background: #222; color: #bada55")
       (run-tests! registry test-results)
       (constantly nil)))

    [:div {:style {:width       "calc(100% - 128px)"
                   :maxMidth    "680px"
                   :marginLeft  "64px"
                   :marginRight "64px"
                   :fontFamily  "'Open Sans', sans-serif"
                   :lineHeight  "1.5"
                   :color       "#24292e"}}
     [:h1 {}
      [:a {:href "https://github.com/wavejumper/rehook"
           :target "_blank"}
       "rehook-test"]]

     [report-summary]]))

(defn inject-stylesheet! [href]
  (let [link (doto (js/document.createElement "link")
               (aset "href" href)
               (aset "rel" "stylesheet")
               (aset "type" "text/css"))
        head (aget js/document "head")]
    (.appendChild head link)))

(defn report []
  (let [system {:registry     rehook.test/registry
                :test-results (atom [])}
        elem   (dom.browser/bootstrap system identity clj->js rehook-summary)]

    (inject-stylesheet! "https://fonts.googleapis.com/css?family=Open+Sans&display=swap")
    (inject-stylesheet! "https://fonts.googleapis.com/icon?family=Material+Icons")
    (inject-stylesheet! "https://crowley.kibu.com.au/rehook/styles/table.css")
    (inject-stylesheet! syntaxcss)

    (react-dom/render elem (js/document.getElementById target))))