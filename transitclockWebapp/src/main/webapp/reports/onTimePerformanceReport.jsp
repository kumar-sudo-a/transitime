<%@ page language="java" contentType="text/html; charset=ISO-8859-1"
         pageEncoding="ISO-8859-1"%>
<!DOCTYPE html PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN" "http://www.w3.org/TR/html4/loose.dtd">
<html>
    <head>
        <%@include file="/template/includes.jsp" %>
        <meta http-equiv="Content-Type" content="text/html; charset=ISO-8859-1">
        <title>On Time Performance</title>

        <!-- Load in Select2 files so can create fancy route selector -->
        <link href="//cdnjs.cloudflare.com/ajax/libs/select2/4.0.0/css/select2.min.css" rel="stylesheet" />
        <script src="//cdnjs.cloudflare.com/ajax/libs/select2/4.0.0/js/select2.min.js"></script>

        <link href="params/reportParams.css" rel="stylesheet"/>
        <style>
            hr {
                height: 2px;
                background-color: darkgray;
                margin-right: 5px;
            }

            label {
                text-align: left;
                width: auto;
            }

            #paramsSidebar {
                width: 20%;
                margin-left: 10px;
                float:left;
                border-right: 1px solid black;
                border-bottom: 1px solid black;
            }
        </style>
<%--        <script type="text/javascript" src="http://ajax.googleapis.com/ajax/libs/jquery/1.11.0/jquery.min.js"></script>--%>
        <link rel="stylesheet" type="text/css" href="../jquery.datepick.package-5.1.0/css/jquery.datepick.css">
        <script type="text/javascript" src="../jquery.datepick.package-5.1.0/js/jquery.plugin.js"></script>
        <script type="text/javascript" src="../jquery.datepick.package-5.1.0/js/jquery.datepick.js"></script>
    </head>
    <body>
        <%@include file="/template/header.jsp" %>
        <div id="paramsSidebar">
            <div id="title" style="text-align: left; font-size:x-large">
                On Time Performance Report
            </div>

            <div id="paramsFields">
                <%-- For passing agency param to the report --%>
                <input type="hidden" name="a" value="<%= request.getParameter("a")%>">

                <jsp:include page="params/routeAllOrSingle.jsp" />
<%--                <jsp:include page="params/fromDateNumDaysTime.jsp" />--%>

                <%-- For specifying a begin date, number of days, begin time, and end time --%>

                <script src="../javascript/jquery-timepicker/jquery.timepicker.min.js"></script>
                <link rel="stylesheet" type="text/css" href="../javascript/jquery-timepicker/jquery.timepicker.css"></link>

                <script>
                    $(function() {
                        var calendarIconTooltip = "Popup calendar to select date";

                        $( "#beginDate" ).datepick({
                            dateFormat: "yy-mm-dd",
                            showOtherMonths: true,
                            selectOtherMonths: true,
                            // Show button for calendar
                            buttonImage: "img/calendar.gif",
                            buttonImageOnly: true,
                            showOn: "both",
                            // Don't allow going past current date
                            maxDate: 0,
                            // onClose is for restricting end date to be after start date,
                            // though it is potentially confusing to user
                            rangeSelect: true,
                            showTrigger: '<button type="button" class="trigger">' +
                                '<img src="../jquery.datepick.package-5.1.0/img/calendar.gif" alt="Popup"></button>',
                            onClose: function( selectedDate ) {
                                // Strangely need to set the title attribute for the icon again
                                // so that don't revert back to a "..." tooltip
                                // FIXME $(".ui-datepicker-trigger").attr("title", calendarIconTooltip);
                            }
                        });

                        // Use a better tooltip than the default "..." for the calendar icon
                        $(".ui-datepicker-trigger").attr("title", calendarIconTooltip);

                        $("#beginTime, #endTime").timepicker({timeFormat: "H:i"})
                            .on('change', function(evt) {
                                if (evt.originalEvent) { // manual change
                                    // validate that this looks like HH:MM
                                    if (!evt.target.value.match(/^(([0,1][0-9])|(2[0-3])):[0-5][0-9]$/))
                                        evt.target.value = evt.target.oldval ? evt.target.oldval : "";
                                }
                                evt.target.oldval = evt.target.value;
                            });

                    });
                </script>

                <div class="param">
                    <label for="beginDate">Date:</label>
                    <input type="text" id="beginDate" name="beginDate"
                           title="The range of dates that you want to examine data for.
                           <br><br> Begin date must be before the end date."
                           size="18"
                           value="Date range" />
                </div>

                <div class="param">
                    <label for="beginTime">Begin Time:</label>
                    <input id="beginTime" name="beginTime"
                           title="Optional begin time of day to limit query to. Useful if
                            want to see result just for rush hour, for example. Leave blank
                            if want data for entire day.
                            <br/><br/>Format: hh:mm, as in '07:00' for 7AM."
                           size="5"
                           value="" /> <span class="note">(hh:mm)</span>
                </div>

                <div class="param">
                    <label for="endTime">End Time:</label>
                    <input id="endTime" name="endTime"
                           title="Optional end time of day to limit query to. Useful if
                            want to see result just for rush hour, for example. Leave blank
                            if want data for entire day.
                            <br/><br/>Format: hh:mm, as in '09:00' for 9AM.
                            Use '23:59' for midnight."
                           size="5"
                           value="" /> <span class="note">(hh:mm)</span>
                </div>

                <div class="param">
<%--                    <i id="reportSettings" class="fa fa-caret-right"></i>Report Settings--%>
<%--                    <button id="reportSettings" onclick="$('#radioButtons').toggle();"></button>--%>
                    Report Settings

                    <div id="radioButtons" style="margin-top: 10px;">
                        <input type="radio" name="stopType" id="timePointsOnly">Timepoints Only
                        <br>
                        <input type="radio" name="stopType" checked="checked">All stops
                    </div>
                </div>

                <hr>
                OTP Definition
                <br>

                <div class="param" style="display: inline-block">
                    <label for="early">Min Early:</label>
                    <input type="number" id="early" name="early" min="0" max="1440" step="0.5" value="1.5">
                </div>
                <div class="param" style="display: inline-block">
                    <label for="late">Min Late:</label>
                    <input type="number" id="late" name="late" min="0" max="1440" step="0.5" value="2.5">
                </div>

                <hr>

                <div class="param">
                    <label for="serviceDayType" style="margin-top: 0px;">Service Day Type:</label>
                    <select id="serviceDayType" name="serviceDayType">
                        <option value="">All</option>
                        <option value="weekday">Weekday</option>
                        <option value="saturday">Saturday</option>
                        <option value="sunday">Sunday</option>
                    </select>
                </div>

                <hr>
            </div>

            <input type="button" id="submit" value="Submit" style="margin-top: 10px; margin-bottom: 10px;">

        </div>

        <script src="https://cdn.jsdelivr.net/npm/chart.js@2.8.0"></script>
        <script src="https://cdn.jsdelivr.net/gh/emn178/chartjs-plugin-labels/src/chartjs-plugin-labels.js"></script>

        <h2 style="text-align: center; margin-top: 20px;">On Time Performance By Route</h2>
        <div id="reportResults" style="width: 79%; display: inline-block;">
            <canvas id="chartCanvas" style="margin-top: 10px;"></canvas>
            <div id="chartTotal" style="text-align: center;"></div>
        </div>
    </body>
</html>

<script>
    $("#route").attr("style", "width: 200px");

    var canvas = $("#chartCanvas");
    var pieChart = new Chart(canvas, {
        type: 'pie',
        data: {
            datasets: [{
                data: [],
                backgroundColor: ['#fffe00', '#ff2000', '#0ca900']
            }],
            labels: ['Early', 'Late', 'On time']
        },
        options: {
            legend: {
                position: 'bottom'
            },
            plugins: {
                labels: {
                    render: function(args) {
                        return args.value + "\n(" + args.percentage + "%)";
                    },
                    fontSize: 24,
                    fontColor: '#000000',
                    position: 'border',
                    precision: 1
                }
            }
        }
    });

    $("#submit").click(function() {
        $("#submit").attr("disabled","disabled");

        if ($("#beginDate").val() == "Date range") {
            var today = new Date();
            var beginDate = endDate = today.getFullYear() + "-"
                + (today.getMonth() <= 10 ? "0" + (today.getMonth() + 1) : (today.getMonth() + 1))
                + "-" + (today.getDate() < 10 ? "0" + today.getDate() : today.getDate());
        }
        else {
            var dateRangeStrings = $("#beginDate").val().replace(/\s/g, "").split("-");
            var beginYear = "20" + dateRangeStrings[0];
            var endYear = "20" + dateRangeStrings[3];
            var beginDate = [beginYear,  dateRangeStrings[1],  dateRangeStrings[2]].join("-");
            var endDate = [endYear, dateRangeStrings[4], dateRangeStrings[5]].join("-");
        }

        var beginTime = $("#beginTime").val() == "" ? "00:00:00" : $("#beginTime").val() + ":00";
        var endTime = $("#endTime").val() == "" ? "23:59:59" : $("#endTime").val() + ":00";

        request = {};
        request.beginDate = beginDate;
        request.beginTime = beginTime;
        request.endDate = endDate;
        request.endTime = endTime;
        request.r = $("#route").val();
        request.minEarlyMSec = $("#early").val() * 60000;
        request.minLateMSec = $("#late").val() * 60000;
        request.serviceType = $("#serviceDayType").val();
        request.timePointsOnly = $("#timePointsOnly")[0].checked;

        $.ajax({
            url: apiUrlPrefix + "/report/chartjs/onTimePerformanceByRoute",
            // Pass in query string parameters to page being requested
            data: request,
            // Needed so that parameters passed properly to page being requested
            traditional: true,
            dataType:"json",
            success: drawChart
        })
    })

    function drawChart(response) {
        $("#submit").removeAttr("disabled");
        var values = response.data.datasets[0].data
        pieChart.data.datasets[0].data = values;
        pieChart.update();
        $("#chartTotal").html("Total count: " + values.reduce(function(total, num) {return total + num}));
    }
</script>