# Building a Modern ClojureScript SPA: A Comprehensive Tutorial

This tutorial guides you through building a production-ready task management application using ClojureScript with the latest tools and best practices for 2024-2025. We'll use shadow-cljs, re-frame with React 19 compatibility via RFX/hsx, shadow-css with Material UI, and emphasize REPL-driven development throughout.

## Part 1: Environment Setup and Project Initialization

### Prerequisites
- Node.js 18+ and npm
- Java 11+
- VS Code on Mac
- Basic ClojureScript knowledge

### Step 1: Create Project Structure

```bash
# Create project directory
mkdir task-manager && cd task-manager

# Initialize shadow-cljs project
npx create-cljs-project task-manager
```

### Step 2: Configure Dependencies

Update `deps.edn`:
```clojure
{:paths ["src/main" "src/dev"]
 :deps {org.clojure/clojure {:mvn/version "1.11.1"}
        org.clojure/clojurescript {:mvn/version "1.11.60"}
        thheller/shadow-cljs {:mvn/version "2.28.18"}
        
        ;; RFX for React 19 compatibility
        io.factorhouse/rfx {:mvn/version "0.1.13"}
        io.factorhouse/re-frame-bridge {:mvn/version "0.1.13"}
        io.factorhouse/hsx {:mvn/version "0.1.13"}
        
        ;; Styling
        thheller/shadow-css {:mvn/version "LATEST"}
        arttuka/reagent-material-ui {:mvn/version "5.11.12-0"}
        
        ;; Routing
        metosin/reitit {:mvn/version "0.7.0-alpha7"}}}
```

Update `shadow-cljs.edn`:
```clojure
{:source-paths ["src/main" "src/dev" "src/test"]
 :dependencies [[binaryage/devtools "1.0.7"]
                [day8.re-frame/re-frame-10x "1.6.0"]]
 
 :dev-http {8080 "public"
            8081 "out/test"}
 
 :builds
 {:app {:target :browser
        :output-dir "public/js"
        :asset-path "/js"
        :modules {:main {:init-fn task-manager.core/init!}}
        
        :devtools {:before-load task-manager.core/stop
                   :after-load task-manager.core/start
                   :watch-dir "public"
                   :preloads [devtools.preload
                              day8.re-frame-10x.preload]}
        
        :compiler-options {:source-map true
                          :source-map-include-sources-content true}}
  
  :test {:target :browser-test
         :test-dir "out/test"
         :ns-regexp "-test$"}
         
  :node-test {:target :node-test
              :output-to "out/node-tests.js"
              :autorun true}}}
```

### Step 3: VS Code + Calva Setup

Create `.vscode/settings.json`:
```json
{
  "calva.paredit.defaultKeyMap": "strict",
  "calva.fmt.formatAsYouType": true,
  "calva.evalOnSave": false,
  "calva.jackIn.connectSequence": [
    {
      "name": "shadow-cljs",
      "projectType": "shadow-cljs",
      "cljsType": "shadow-cljs",
      "builds": ["app", "test"]
    }
  ],
  "calva.customREPLCommandSnippets": [
    {
      "name": "Tap current form",
      "snippet": "(tap> $current-form)"
    },
    {
      "name": "Inspect app state",
      "snippet": "@re-frame.db/app-db"
    }
  ]
}
```

Create `.clj-kondo/config.edn`:
```clojure
{:lint-as {reagent.core/with-let clojure.core/let
           re-frame.core/reg-sub clojure.core/defn
           re-frame.core/reg-event-fx clojure.core/defn
           re-frame.core/reg-event-db clojure.core/defn}
 :linters {:unresolved-symbol {:level :error
                               :exclude [js/console
                                        js/document
                                        js/window
                                        js/fetch]}}}
```

### Step 4: CSS Build Setup

Create `build.clj`:
```clojure
(ns build
  (:require [shadow.css.build :as cb]
            [clojure.java.io :as io]))

(defn css-release [& args]
  (let [build-state
        (-> (cb/start)
            (cb/index-path (io/file "src" "main") {})
            (cb/generate '{:ui {:entries [task-manager.styles]}})
            (cb/minify)
            (cb/write-outputs-to (io/file "public" "css")))]
    (doseq [mod (vals (:chunks build-state))
            {:keys [warning-type] :as warning} (:warnings mod)]
      (prn [:CSS (name warning-type) (dissoc warning :warning-type)]))))
```

Create `src/dev/repl.clj`:
```clojure
(ns repl
  (:require [shadow.cljs.devtools.api :as shadow]
            [shadow.cljs.devtools.server.fs-watch :as fs-watch]
            [clojure.java.io :as io]
            [build]))

(defonce css-watch-ref (atom nil))

(defn start []
  (shadow/watch :app)
  (build/css-release)
  (reset! css-watch-ref
    (fs-watch/start
      {}
      [(io/file "src" "main")]
      ["cljs" "cljc" "clj"]
      (fn [_]
        (try
          (build/css-release)
          (catch Exception e
            (prn [:css-failed e]))))))
  ::started)

(defn stop []
  (shadow/stop-worker :app)
  (when-let [css-watch @css-watch-ref]
    (fs-watch/stop css-watch))
  ::stopped)

(defn go []
  (stop)
  (start))
```

## Part 2: Core Application Structure

### Step 1: HTML Template

Create `public/index.html`:
```html
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Task Manager</title>
    <link rel="stylesheet" href="/css/ui.css">
    <link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Inter:wght@300;400;500;700&display=swap">
    <link rel="stylesheet" href="https://fonts.googleapis.com/icon?family=Material+Icons">
</head>
<body>
    <div id="app"></div>
    <script src="/js/main.js"></script>
</body>
</html>
```

### Step 2: Core Application Entry Point

Create `src/main/task_manager/core.cljs`:
```clojure
(ns task-manager.core
  (:require [io.factorhouse.rfx.core :as rfx]
            [io.factorhouse.re-frame-bridge :as rfb]
            [io.factorhouse.hsx.core :as hsx]
            [task-manager.events :as events]
            [task-manager.subs :as subs]
            [task-manager.views.app :as views]
            [task-manager.routes :as routes]))

(defn ^:dev/before-load stop []
  (js/console.log "Stopping app..."))

(defn ^:dev/after-load start []
  (js/console.log "Starting app...")
  (rfx/clear-subscription-cache!)
  (mount-root))

(defn mount-root []
  (when-let [el (.getElementById js/document "app")]
    (rfx/render [views/app-root] el)))

(defn init! []
  (routes/init-routes!)
  (rfb/dispatch-sync [:initialize-db])
  (mount-root))
```

### Step 3: Database Schema

Create `src/main/task_manager/db.cljs`:
```clojure
(ns task-manager.db)

(def default-db
  {:tasks {:by-id {}
           :all-ids []}
   
   :filters {:status :all        ; :all, :active, :completed
             :search ""
             :sort-by :created-at
             :sort-order :desc}
   
   :ui {:loading? false
        :selected-task nil
        :modal-open? false
        :theme-mode "light"
        :snackbar {:open? false
                   :message ""
                   :severity :info}}
   
   :forms {:new-task {:title ""
                      :description ""
                      :priority :medium
                      :due-date nil
                      :tags []}}
   
   :pagination {:current-page 1
                :page-size 20
                :total-count 0}
   
   :current-route nil})

;; Task model
(defn create-task [title]
  {:id (str (random-uuid))
   :title title
   :description ""
   :status :pending
   :priority :medium
   :created-at (js/Date.)
   :updated-at (js/Date.)
   :due-date nil
   :tags []})
```

### Step 4: Events Layer

Create `src/main/task_manager/events.cljs`:
```clojure
(ns task-manager.events
  (:require [io.factorhouse.re-frame-bridge :as rfb]
            [task-manager.db :as db]))

;; Interceptors
(def debug-interceptor
  (rfb/after (fn [db event]
              (js/console.log "Event:" (pr-str event))
              (tap> {:event event :db db}))))

;; Initialize app
(rfb/reg-event-db
 :initialize-db
 [debug-interceptor]
 (fn [_ _]
   db/default-db))

;; Navigation
(rfb/reg-event-db
 :navigate
 (fn [db [_ route]]
   (assoc db :current-route route)))

;; Task CRUD operations
(rfb/reg-event-db
 :task/create
 [debug-interceptor]
 (fn [db [_ title]]
   (let [task (db/create-task title)
         id (:id task)]
     (-> db
         (assoc-in [:tasks :by-id id] task)
         (update-in [:tasks :all-ids] conj id)))))

(rfb/reg-event-db
 :task/update
 (fn [db [_ id updates]]
   (update-in db [:tasks :by-id id] merge updates {:updated-at (js/Date.)})))

(rfb/reg-event-db
 :task/delete
 (fn [db [_ id]]
   (-> db
       (update-in [:tasks :by-id] dissoc id)
       (update-in [:tasks :all-ids] #(filterv (partial not= id) %)))))

(rfb/reg-event-db
 :task/toggle-complete
 (fn [db [_ id]]
   (update-in db [:tasks :by-id id :status]
              #(if (= % :completed) :pending :completed))))

;; UI Events
(rfb/reg-event-db
 :ui/set-loading
 (fn [db [_ loading?]]
   (assoc-in db [:ui :loading?] loading?)))

(rfb/reg-event-db
 :ui/show-snackbar
 (fn [db [_ message severity]]
   (assoc-in db [:ui :snackbar] {:open? true 
                                  :message message 
                                  :severity severity})))

(rfb/reg-event-db
 :ui/hide-snackbar
 (fn [db _]
   (assoc-in db [:ui :snackbar :open?] false)))

;; Filter events
(rfb/reg-event-db
 :filter/set-status
 (fn [db [_ status]]
   (assoc-in db [:filters :status] status)))

(rfb/reg-event-db
 :filter/set-search
 (fn [db [_ search]]
   (assoc-in db [:filters :search] search)))

;; Form events
(rfb/reg-event-db
 :form/update-field
 (fn [db [_ form-id field value]]
   (assoc-in db [:forms form-id field] value)))

(rfb/reg-event-db
 :form/reset
 (fn [db [_ form-id]]
   (assoc-in db [:forms form-id] (get-in db/default-db [:forms form-id]))))

;; Theme
(rfb/reg-event-db
 :theme/toggle
 (fn [db _]
   (update-in db [:ui :theme-mode] {"light" "dark" "dark" "light"})))
```

## Part 3: Subscriptions and Views

### Step 1: Subscriptions Layer

Create `src/main/task_manager/subs.cljs`:
```clojure
(ns task-manager.subs
  (:require [io.factorhouse.re-frame-bridge :as rfb]
            [clojure.string :as str]))

;; Raw subscriptions
(rfb/reg-sub :tasks/by-id (fn [db _] (get-in db [:tasks :by-id])))
(rfb/reg-sub :tasks/all-ids (fn [db _] (get-in db [:tasks :all-ids])))
(rfb/reg-sub :filters (fn [db _] (:filters db)))
(rfb/reg-sub :ui/loading? (fn [db _] (get-in db [:ui :loading?])))
(rfb/reg-sub :ui/theme-mode (fn [db _] (get-in db [:ui :theme-mode])))
(rfb/reg-sub :ui/snackbar (fn [db _] (get-in db [:ui :snackbar])))
(rfb/reg-sub :current-route (fn [db _] (:current-route db)))
(rfb/reg-sub :form (fn [db [_ form-id]] (get-in db [:forms form-id])))

;; Derived subscriptions
(rfb/reg-sub
 :tasks/all
 :<- [:tasks/by-id]
 :<- [:tasks/all-ids]
 (fn [[by-id all-ids] _]
   (mapv by-id all-ids)))

(rfb/reg-sub
 :tasks/filtered
 :<- [:tasks/all]
 :<- [:filters]
 (fn [[tasks filters] _]
   (let [{:keys [status search]} filters]
     (cond->> tasks
       (not= status :all)
       (filter #(case status
                  :active (= (:status %) :pending)
                  :completed (= (:status %) :completed)))
       
       (not (str/blank? search))
       (filter #(str/includes? 
                 (str/lower-case (:title %))
                 (str/lower-case search)))))))

(rfb/reg-sub
 :tasks/sorted
 :<- [:tasks/filtered]
 :<- [:filters]
 (fn [[tasks {:keys [sort-by sort-order]}] _]
   (let [sorted (sort-by sort-by tasks)]
     (if (= sort-order :desc)
       (reverse sorted)
       sorted))))

(rfb/reg-sub
 :tasks/stats
 :<- [:tasks/all]
 (fn [tasks _]
   {:total (count tasks)
    :active (count (filter #(= (:status %) :pending) tasks))
    :completed (count (filter #(= (:status %) :completed) tasks))}))
```

### Step 2: Styling with shadow-css

Create `src/main/task_manager/styles.cljs`:
```clojure
(ns task-manager.styles
  {:shadow.css/include ["task_manager/components/styles.cljs"
                        "task_manager/views/styles.cljs"]}
  (:require [shadow.css :refer (css)]))

;; Base styles
(def $root
  (css :min-h-screen :bg-gray-50
       {:font-family "Inter, -apple-system, BlinkMacSystemFont, sans-serif"}))

(def $container
  (css :max-w-6xl :mx-auto :px-4 :py-8))

;; Layout components
(def $card
  (css :bg-white :rounded-lg :shadow :p-6
       [:hover {:box-shadow "0 4px 12px rgba(0,0,0,0.1)"}]))

(def $flex-between
  (css :flex :items-center :justify-between))

(def $form-field
  (css :mb-4 :w-full))

;; Task-specific styles
(def $task-item
  (css :flex :items-center :py-3 :border-b :border-gray-200
       :transition-all :duration-200
       [:hover {:background-color "#f9fafb"}]
       [:last-child {:border-bottom-width 0}]))

(def $task-completed
  (css {:text-decoration "line-through"
        :color "#9ca3af"}))

(def $priority-badge
  (css :px-2 :py-1 :rounded :text-xs :font-medium
       [:&.high {:background-color "#fee2e2" :color "#dc2626"}]
       [:&.medium {:background-color "#fef3c7" :color "#d97706"}]
       [:&.low {:background-color "#dbeafe" :color "#2563eb"}]))

;; Dark mode support
(def $dark-mode
  (css ["[data-theme='dark'] &" 
        {:background-color "#1f2937"
         :color "#f9fafb"}]))
```

### Step 3: Main App View

Create `src/main/task_manager/views/app.cljs`:
```clojure
(ns task-manager.views.app
  (:require [io.factorhouse.rfx.core :as rfx]
            [io.factorhouse.re-frame-bridge :as rfb]
            [reagent-mui.material.app-bar :as mui-app-bar]
            [reagent-mui.material.toolbar :as mui-toolbar]
            [reagent-mui.material.typography :as mui-typography]
            [reagent-mui.material.container :as mui-container]
            [reagent-mui.material.icon-button :as mui-icon-button]
            [reagent-mui.material.theme-provider :as mui-theme-provider]
            [reagent-mui.material.css-baseline :as mui-css-baseline]
            [reagent-mui.icons.dark-mode :as dark-mode-icon]
            [reagent-mui.icons.light-mode :as light-mode-icon]
            [reagent-mui.material.snackbar :as mui-snackbar]
            [reagent-mui.material.alert :as mui-alert]
            ["@mui/material/styles" :as mui-styles]
            [shadow.css :refer (css)]
            [task-manager.views.pages.dashboard :as dashboard]
            [task-manager.views.pages.tasks :as tasks-page]
            [task-manager.styles :as styles]))

(defn create-theme [mode]
  (mui-styles/createTheme
    #js {:palette #js {:mode mode
                       :primary #js {:main "#2563eb"}
                       :secondary #js {:main "#7c3aed"}}
         :typography #js {:fontFamily "Inter, sans-serif"}}))

(defn header []
  (let [dispatch (rfx/use-dispatch)
        theme-mode @(rfb/use-sub [:ui/theme-mode])]
    [mui-app-bar/app-bar {:position "static"}
     [mui-toolbar/toolbar
      [mui-typography/typography {:variant "h6" :sx #js {:flexGrow 1}}
       "Task Manager"]
      [mui-icon-button/icon-button 
       {:onClick #(dispatch [:theme/toggle])}
       (if (= theme-mode "light")
         [dark-mode-icon/dark-mode]
         [light-mode-icon/light-mode])]]]))

(defn snackbar []
  (let [dispatch (rfx/use-dispatch)
        {:keys [open? message severity]} @(rfb/use-sub [:ui/snackbar])]
    [mui-snackbar/snackbar
     {:open open?
      :autoHideDuration 6000
      :onClose #(dispatch [:ui/hide-snackbar])}
     [mui-alert/alert 
      {:severity severity
       :onClose #(dispatch [:ui/hide-snackbar])}
      message]]))

(defn router []
  (let [route @(rfb/use-sub [:current-route])]
    (case (:name route)
      :dashboard [dashboard/page]
      :tasks [tasks-page/page]
      [dashboard/page])))

(defn app-root []
  (let [theme-mode @(rfb/use-sub [:ui/theme-mode])
        theme (create-theme theme-mode)]
    [:div {:class styles/$root
           :data-theme theme-mode}
     [:> mui-theme-provider/theme-provider {:theme theme}
      [mui-css-baseline/css-baseline]
      [header]
      [mui-container/container {:maxWidth "lg" :className styles/$container}
       [router]]
      [snackbar]]]))
```

## Part 4: Building Core Features

### Step 1: Task List Component

Create `src/main/task_manager/views/pages/tasks.cljs`:
```clojure
(ns task-manager.views.pages.tasks
  (:require [io.factorhouse.rfx.core :as rfx]
            [io.factorhouse.re-frame-bridge :as rfb]
            [reagent-mui.material.paper :as mui-paper]
            [reagent-mui.material.typography :as mui-typography]
            [reagent-mui.material.button :as mui-button]
            [reagent-mui.material.text-field :as mui-text-field]
            [reagent-mui.material.checkbox :as mui-checkbox]
            [reagent-mui.material.icon-button :as mui-icon-button]
            [reagent-mui.material.chip :as mui-chip]
            [reagent-mui.material.tabs :as mui-tabs]
            [reagent-mui.material.tab :as mui-tab]
            [reagent-mui.icons.delete :as delete-icon]
            [reagent-mui.icons.add :as add-icon]
            [shadow.css :refer (css)]
            [task-manager.styles :as styles]
            [task-manager.components.task-form :as task-form]))

(defn task-item [{:keys [id title description status priority tags]}]
  (let [dispatch (rfx/use-dispatch)
        $checkbox (css :mr-3)
        $content (css :flex-1)
        $actions (css :ml-2)]
    [:div {:class styles/$task-item}
     [mui-checkbox/checkbox 
      {:checked (= status :completed)
       :className $checkbox
       :onChange #(dispatch [:task/toggle-complete id])}]
     [:div {:class $content}
      [:div {:class (when (= status :completed) styles/$task-completed)}
       title]
      (when (seq description)
        [:div {:class (css :text-sm :text-gray-600 :mt-1)}
         description])
      [:div {:class (css :flex :gap-2 :mt-2)}
       [mui-chip/chip 
        {:label priority
         :size "small"
         :className (str "priority-badge " (name priority))}]
       (for [tag tags]
         ^{:key tag}
         [mui-chip/chip {:label tag :size "small" :variant "outlined"}])]]
     [:div {:class $actions}
      [mui-icon-button/icon-button 
       {:size "small"
        :onClick #(dispatch [:task/delete id])}
       [delete-icon/delete]]]]))

(defn task-filters []
  (let [dispatch (rfx/use-dispatch)
        filters @(rfb/use-sub [:filters])
        stats @(rfb/use-sub [:tasks/stats])]
    [:div {:class (css :mb-4)}
     [mui-tabs/tabs 
      {:value (:status filters)
       :onChange (fn [_ value] (dispatch [:filter/set-status value]))}
      [mui-tab/tab {:label (str "All (" (:total stats) ")")
                    :value :all}]
      [mui-tab/tab {:label (str "Active (" (:active stats) ")")
                    :value :active}]
      [mui-tab/tab {:label (str "Completed (" (:completed stats) ")")
                    :value :completed}]]
     [mui-text-field/text-field
      {:placeholder "Search tasks..."
       :value (:search filters)
       :onChange #(dispatch [:filter/set-search (.. % -target -value)])
       :size "small"
       :fullWidth true
       :className (css :mt-4)}]]))

(defn task-list []
  (let [tasks @(rfb/use-sub [:tasks/sorted])]
    [:<>
     (if (empty? tasks)
       [mui-typography/typography 
        {:color "text.secondary" :align "center" :className (css :py-8)}
        "No tasks found"]
       (for [task tasks]
         ^{:key (:id task)}
         [task-item task]))]))

(defn page []
  (let [$add-button (css :mb-4)]
    [:<>
     [mui-typography/typography {:variant "h4" :className (css :mb-6)}
      "Tasks"]
     [task-form/new-task-form]
     [mui-paper/paper {:className styles/$card}
      [task-filters]
      [task-list]]]))
```

### Step 2: Task Form Component

Create `src/main/task_manager/components/task_form.cljs`:
```clojure
(ns task-manager.components.task-form
  (:require [io.factorhouse.rfx.core :as rfx]
            [io.factorhouse.re-frame-bridge :as rfb]
            [reagent.core :as r]
            [reagent-mui.material.dialog :as mui-dialog]
            [reagent-mui.material.dialog-title :as mui-dialog-title]
            [reagent-mui.material.dialog-content :as mui-dialog-content]
            [reagent-mui.material.dialog-actions :as mui-dialog-actions]
            [reagent-mui.material.button :as mui-button]
            [reagent-mui.material.text-field :as mui-text-field]
            [reagent-mui.material.select :as mui-select]
            [reagent-mui.material.menu-item :as mui-menu-item]
            [reagent-mui.material.form-control :as mui-form-control]
            [reagent-mui.material.input-label :as mui-input-label]
            [reagent-mui.icons.add :as add-icon]
            [shadow.css :refer (css)]
            [task-manager.styles :as styles]))

(defn task-dialog [open? on-close]
  (let [dispatch (rfx/use-dispatch)
        form-data @(rfb/use-sub [:form :new-task])
        update-field (fn [field value]
                      (dispatch [:form/update-field :new-task field value]))
        handle-submit (fn []
                       (when (seq (:title form-data))
                         (dispatch [:task/create (:title form-data)])
                         (dispatch [:form/reset :new-task])
                         (dispatch [:ui/show-snackbar "Task created!" :success])
                         (on-close)))]
    [mui-dialog/dialog 
     {:open open?
      :onClose on-close
      :maxWidth "sm"
      :fullWidth true}
     [mui-dialog-title/dialog-title "Create New Task"]
     [mui-dialog-content/dialog-content
      [:form {:onSubmit (fn [e]
                         (.preventDefault e)
                         (handle-submit))}
       [mui-text-field/text-field
        {:label "Title"
         :value (:title form-data)
         :onChange #(update-field :title (.. % -target -value))
         :fullWidth true
         :required true
         :autoFocus true
         :className styles/$form-field}]
       
       [mui-text-field/text-field
        {:label "Description"
         :value (:description form-data)
         :onChange #(update-field :description (.. % -target -value))
         :fullWidth true
         :multiline true
         :rows 3
         :className styles/$form-field}]
       
       [mui-form-control/form-control 
        {:fullWidth true :className styles/$form-field}
        [mui-input-label/input-label "Priority"]
        [mui-select/select
         {:value (:priority form-data)
          :label "Priority"
          :onChange #(update-field :priority (.. % -target -value))}
         [mui-menu-item/menu-item {:value :low} "Low"]
         [mui-menu-item/menu-item {:value :medium} "Medium"]
         [mui-menu-item/menu-item {:value :high} "High"]]]]]
     
     [mui-dialog-actions/dialog-actions
      [mui-button/button {:onClick on-close} "Cancel"]
      [mui-button/button 
       {:onClick handle-submit
        :variant "contained"
        :disabled (empty? (:title form-data))}
       "Create Task"]]]))

(defn new-task-form []
  (let [open? (r/atom false)]
    (fn []
      [:<>
       [mui-button/button
        {:variant "contained"
         :startIcon (r/as-element [add-icon/add])
         :onClick #(reset! open? true)
         :className (css :mb-4)}
        "New Task"]
       [task-dialog @open? #(reset! open? false)]])))
```

## Part 5: REPL-Driven Development Workflow

### Step 1: Starting the Development Environment

```bash
# Terminal 1: Start shadow-cljs
npx shadow-cljs watch app

# Terminal 2: Start CSS build watcher
clojure -M -m repl
(go)  ; This starts CSS watching

# VS Code: Connect to REPL
# Press Cmd+Alt+C Cmd+Alt+C
# Select "shadow-cljs" → "app"
```

### Step 2: REPL Exploration Examples

```clojure
;; After connecting to REPL in VS Code

;; Inspect application state
@re-frame.db/app-db

;; Test creating a task from REPL
(rfb/dispatch [:task/create "Learn REPL-driven development"])

;; Inspect tasks
(:tasks @re-frame.db/app-db)

;; Test task operations
(rfb/dispatch [:task/toggle-complete "task-id-here"])

;; Watch state changes
(add-watch re-frame.db/app-db :debug
  (fn [_ _ old new]
    (when (not= old new)
      (js/console.log "State changed!")
      (tap> {:old old :new new}))))

;; Test subscriptions
@(rfb/subscribe [:tasks/all])
@(rfb/subscribe [:tasks/stats])

;; Modify UI state for testing
(swap! re-frame.db/app-db assoc-in [:ui :loading?] true)
(swap! re-frame.db/app-db assoc-in [:ui :theme-mode] "dark")

;; Performance testing
(time 
  (dotimes [_ 100]
    (rfb/dispatch [:task/create (str "Task " _)])))
```

### Step 3: Component Development in REPL

```clojure
;; Test component rendering
(require '[task-manager.views.pages.tasks :as tasks])

;; Create test data
(rfb/dispatch-sync [:initialize-db])
(rfb/dispatch [:task/create "Test task 1"])
(rfb/dispatch [:task/create "Test task 2"])

;; Inspect component output
(tap> (tasks/task-item {:id "1" :title "Test" :status :pending}))

;; Hot reload component changes
;; Edit component code, save file
;; Changes appear immediately in browser

;; Test different component states
(def test-task
  {:id "test-1"
   :title "REPL Test Task"
   :description "Created from REPL"
   :status :pending
   :priority :high
   :tags ["repl" "test"]})

;; Render test component
(rfx/render [tasks/task-item test-task] 
            (.getElementById js/document "test-container"))
```

## Part 6: Testing Strategy

### Step 1: Unit Tests

Create `src/test/task_manager/events_test.cljs`:
```clojure
(ns task-manager.events-test
  (:require [cljs.test :refer-macros [deftest is testing]]
            [task-manager.events :as events]
            [task-manager.db :as db]))

(deftest task-creation-test
  (testing "Creating a task"
    (let [initial-db db/default-db
          title "Test Task"
          result (events/task-create-handler initial-db [:task/create title])]
      (is (= 1 (count (get-in result [:tasks :all-ids]))))
      (is (= title (-> result 
                       :tasks 
                       :by-id 
                       vals 
                       first 
                       :title))))))

(deftest task-toggle-test
  (testing "Toggling task completion"
    (let [task-id "test-123"
          db-with-task (-> db/default-db
                           (assoc-in [:tasks :by-id task-id] 
                                    {:id task-id :status :pending})
                           (update-in [:tasks :all-ids] conj task-id))
          result (events/task-toggle-handler 
                   db-with-task 
                   [:task/toggle-complete task-id])]
      (is (= :completed (get-in result [:tasks :by-id task-id :status]))))))
```

### Step 2: Integration Tests

Create `src/test/task_manager/integration_test.cljs`:
```clojure
(ns task-manager.integration-test
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [re-frame-test :refer [run-test-sync run-test-async wait-for]]
            [io.factorhouse.re-frame-bridge :as rfb]
            [task-manager.core :as core]))

(deftest full-task-flow-test
  (run-test-sync
    (rfb/dispatch [:initialize-db])
    
    ;; Create task
    (rfb/dispatch [:task/create "Integration Test Task"])
    
    (let [tasks @(rfb/subscribe [:tasks/all])]
      (is (= 1 (count tasks)))
      (is (= "Integration Test Task" (:title (first tasks))))
      
      ;; Toggle completion
      (let [task-id (:id (first tasks))]
        (rfb/dispatch [:task/toggle-complete task-id])
        
        (let [updated-task (first @(rfb/subscribe [:tasks/all]))]
          (is (= :completed (:status updated-task))))))))
```

### Step 3: Running Tests

```bash
# Run browser tests
npx shadow-cljs watch test
# Open http://localhost:8081

# Run node tests
npx shadow-cljs compile node-test
node out/node-tests.js

# Run specific test from REPL
(require '[task-manager.events-test])
(cljs.test/run-tests 'task-manager.events-test)
```

## Part 7: Advanced Features

### Step 1: API Integration with Mocking

Create `src/main/task_manager/api.cljs`:
```clojure
(ns task-manager.api
  (:require [io.factorhouse.re-frame-bridge :as rfb]))

(def ^:dynamic *use-mock-api* true)

;; Mock data store
(def mock-tasks (atom {}))

;; HTTP effect handler
(rfb/reg-fx
 :http-request
 (fn [{:keys [method url params on-success on-failure]}]
   (if *use-mock-api*
     ;; Mock implementation
     (js/setTimeout
       (fn []
         (case [method url]
           ["GET" "/api/tasks"]
           (rfb/dispatch (conj on-success {:tasks (vals @mock-tasks)}))
           
           ["POST" "/api/tasks"]
           (let [task (assoc params :id (str (random-uuid)))]
             (swap! mock-tasks assoc (:id task) task)
             (rfb/dispatch (conj on-success task)))
           
           ["DELETE" (re-matches #"/api/tasks/(.*)" url)]
           (let [[_ id] (re-matches #"/api/tasks/(.*)" url)]
             (swap! mock-tasks dissoc id)
             (rfb/dispatch (conj on-success {:deleted id})))
           
           ;; Default error
           (rfb/dispatch (conj on-failure {:error "Not found"}))))
       100)
     
     ;; Real implementation
     (-> (js/fetch url
                   #js {:method method
                        :headers #js {"Content-Type" "application/json"}
                        :body (when params 
                                (js/JSON.stringify (clj->js params)))})
         (.then #(.json %))
         (.then #(rfb/dispatch (conj on-success (js->clj % :keywordize-keys true))))
         (.catch #(rfb/dispatch (conj on-failure {:error (.-message %)})))))))

;; API Events
(rfb/reg-event-fx
 :api/fetch-tasks
 (fn [{:keys [db]} _]
   {:db (assoc-in db [:ui :loading?] true)
    :http-request {:method "GET"
                   :url "/api/tasks"
                   :on-success [:api/tasks-loaded]
                   :on-failure [:api/request-failed]}}))

(rfb/reg-event-db
 :api/tasks-loaded
 (fn [db [_ response]]
   (-> db
       (assoc-in [:ui :loading?] false)
       (assoc :tasks (reduce (fn [acc task]
                              (-> acc
                                  (assoc-in [:by-id (:id task)] task)
                                  (update :all-ids conj (:id task))))
                            {:by-id {} :all-ids []}
                            (:tasks response))))))
```

### Step 2: Routing Setup

Create `src/main/task_manager/routes.cljs`:
```clojure
(ns task-manager.routes
  (:require [reitit.frontend :as rf]
            [reitit.frontend.easy :as rfe]
            [reitit.coercion.spec :as rss]
            [io.factorhouse.re-frame-bridge :as rfb]))

(def routes
  [["/"
    {:name :dashboard
     :view :dashboard}]
   
   ["/tasks"
    {:name :tasks
     :view :tasks}]
   
   ["/tasks/:id"
    {:name :task-detail
     :view :task-detail
     :parameters {:path {:id string?}}}]])

(defn init-routes! []
  (rfe/start!
    (rf/router routes {:data {:coercion rss/coercion}})
    (fn [match]
      (rfb/dispatch [:navigate match]))
    {:use-fragment false}))
```

### Step 3: Performance Optimization

```clojure
;; Memoized expensive computations
(def get-filtered-tasks
  (memoize
    (fn [tasks filters]
      ;; Expensive filtering logic
      )))

;; Virtual scrolling for large lists
(defn virtual-task-list []
  (let [viewport-height 600
        item-height 80
        visible-start (r/atom 0)]
    (fn [tasks]
      (let [visible-count (Math/ceil (/ viewport-height item-height))
            visible-end (+ @visible-start visible-count)
            visible-tasks (subvec tasks @visible-start visible-end)]
        [:div {:style {:height (str viewport-height "px")
                      :overflow "auto"}
               :on-scroll (fn [e]
                           (let [scroll-top (.. e -target -scrollTop)
                                 new-start (Math/floor (/ scroll-top item-height))]
                             (reset! visible-start new-start)))}
         [:div {:style {:height (str (* (count tasks) item-height) "px")
                       :position "relative"}}
          [:div {:style {:transform (str "translateY(" 
                                        (* @visible-start item-height) 
                                        "px)")}}
           (for [task visible-tasks]
             ^{:key (:id task)}
             [task-item task])]]]))))
```

## Part 8: Production Considerations

### Step 1: Build Configuration

Update `shadow-cljs.edn` for production:
```clojure
{:builds
 {:app {:target :browser
        ;; ... existing config ...
        :release {:compiler-options 
                  {:optimizations :advanced
                   :source-map false
                   :pretty-print false}
                  :module-hash-names true}}}}
```

### Step 2: Deployment Build

```bash
# Production build
npx shadow-cljs release app
clojure -M:build css-release

# Files will be in:
# - public/js/main.[hash].js (optimized, minified)
# - public/css/ui.css (optimized CSS)
```

### Step 3: Error Handling

```clojure
;; Global error boundary
(defn error-boundary []
  (let [error (r/atom nil)]
    (r/create-class
     {:component-did-catch
      (fn [err info]
        (reset! error {:error err :info info})
        (rfb/dispatch [:error/log err info]))
      
      :render
      (fn [this]
        (if @error
          [:div.error-boundary
           [:h2 "Something went wrong"]
           [:button {:on-click #(reset! error nil)} "Try again"]]
          (r/children this)))})))
```

## Summary

This tutorial has covered building a modern ClojureScript SPA with:

1. **Modern Tooling**: shadow-cljs, VS Code + Calva, clj-kondo, clojure-lsp
2. **React 19 Compatibility**: Using RFX/re-frame-bridge with hsx syntax
3. **Styling**: shadow-css for build-time CSS with Material UI components
4. **REPL-Driven Development**: Interactive development workflow
5. **Testing**: Comprehensive testing strategy with REPL integration
6. **Production Ready**: Optimized builds, error handling, and performance

The key to ClojureScript development is embracing the REPL-driven workflow. Use it to explore, test, and develop interactively. The combination of modern tooling with ClojureScript's powerful abstractions creates an excellent developer experience for building complex SPAs.