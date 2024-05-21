package org.omoknoone.ppm.domain.projectDashboard.service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.modelmapper.ModelMapper;
import org.modelmapper.TypeToken;
import org.omoknoone.ppm.domain.employee.service.EmployeeService;
import org.omoknoone.ppm.domain.project.service.ProjectService;
import org.omoknoone.ppm.domain.projectDashboard.aggregate.Graph;
import org.omoknoone.ppm.domain.projectDashboard.dto.GraphDTO;
import org.omoknoone.ppm.domain.projectDashboard.repository.GraphRepository;
import org.omoknoone.ppm.domain.projectmember.dto.viewProjectMembersByProjectResponseDTO;
import org.omoknoone.ppm.domain.projectmember.service.ProjectMemberService;
import org.omoknoone.ppm.domain.schedule.service.ScheduleService;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;


@Slf4j
@Service
@RequiredArgsConstructor
public class GraphServiceImpl implements GraphService {

    private final GraphRepository graphRepository;
    private final ScheduleService scheduleService;
    private final MongoTemplate mongoTemplate;
    private final ModelMapper modelMapper;
    private final ProjectMemberService projectMemberService;
    private final EmployeeService employeeService;
    private final ProjectService projectService;

    // init
    // 프로젝트가 생성될 때 대시보드가 초기값으로 생성 되어야함
    public void initGraph(String projectId, String projectMemberId, int[] expectedProgress) {

        // 게이지
        List<Map<String, Object>> gaugeSeries = List.of(
            Map.of(
                "name", "전체진행률",
                "data", 0
            )
        );

        // 테이블
        List<Map<String, Object>> tableSeries = new ArrayList<>();
        List<viewProjectMembersByProjectResponseDTO> members = projectMemberService.viewProjectMembersByProject(Integer.valueOf(projectId));

        for (viewProjectMembersByProjectResponseDTO member : members) {
            String employeeName = employeeService.getEmployeeNameByProjectMemberId(member.getProjectMemberEmployeeId());
            Map<String, Object> row = new HashMap<>();
            row.put("구성원명", employeeName);
            row.put("준비", 0);
            row.put("진행", 0);
            row.put("완료", 0);
            tableSeries.add(row);
        }


        // 파이
        List<Map<String, Object>> pieSeries = List.of(
            Map.of(
                "name", "준비",
                "data", 0
            ),
            Map.of(
                "name", "진행",
                "data", 0
            ),
            Map.of(
                "name", "완료",
                "data", 0
            )
        );

        // 라인
        List<Map<String, Object>> lineSeries = List.of(
            Map.of(
                "name", "예상진행률",
                "data", expectedProgress
            ),
            Map.of(
                "name", "실제진행률",
                "data", new int[10]
            )
        );

        // 프로젝트 시작일, 종료일 저장
        List<String> lineCategories = new ArrayList<>(10);
        for (int i = 0; i < 10; i++) {
            lineCategories.add("");
        }
        String pattern = "dd/MM/yyyy";

        LocalDate startDate = projectService.viewStartDate(Integer.valueOf(projectId));
        LocalDate endDate = projectService.viewEndDate(Integer.valueOf(projectId));

        lineCategories.set(0, startDate.format(DateTimeFormatter.ofPattern(pattern)));
        lineCategories.set(9, endDate.format(DateTimeFormatter.ofPattern(pattern)));

        // 컬럼
        List<Map<String, Object>> columnSeries = List.of(
            Map.of(
                "name", "준비",
                "data", new int[3]
            ),
            Map.of(
                "name", "진행",
                "data", new int[3]
            ),
            Map.of(
                "name", "완료",
                "data", new int[3]
            )
        );

        // 그래프 생성
        Graph gaugeGraph = Graph.builder()
            .projectId(projectId)
            .projectMemberId(projectMemberId)
            .type("gauge")
            .series(gaugeSeries)
            .build();

        Graph tableGraph = Graph.builder()
            .projectId(projectId)
            .projectMemberId(projectMemberId)
            .type("table")
            .series(tableSeries)
            .build();

        Graph pieGraph = Graph.builder()
            .projectId(projectId)
            .projectMemberId(projectMemberId)
            .type("pie")
            .series(pieSeries)
            .build();

        Graph lineGraph = Graph.builder()
            .projectId(projectId)
            .projectMemberId(projectMemberId)
            .type("line")
            .series(lineSeries)
            .categories(lineCategories)
            .build();

        // 추후 추가
        // Graph columnGraph = Graph.builder()
        //     .projectId(projectId)
        //     .projectMemberId(projectMemberId)
        //     .type("column")
        //     .series(columnSeries)
        //     .build();

        // 저장
        graphRepository.saveAll(List.of(gaugeGraph, tableGraph, pieGraph, lineGraph/*, columnGraph*/));
    }



    // 프로젝트 Id를 통해 대시보드(그래프) 조회
    @Transactional(readOnly = true)
    public List<GraphDTO> viewProjectDashboardByProjectId(String projectId) {

        List<Graph> graphs = graphRepository.findAllByProjectId(projectId);
        return modelMapper.map(graphs, new TypeToken<List<Graph>>() {
        }.getType());
    }


    // 전체진행률 (게이지) 업데이트
    @Transactional
    public void updateGauge(String projectId) {

        Criteria criteria = new Criteria().andOperator(
                Criteria.where("projectId").is(projectId),
                Criteria.where("type").is("gauge"),
                Criteria.where("series.name").is("전체진행률")
        );

        Query query = new Query(criteria);

        Update update = new Update();
        update.set("series.$.data", scheduleService.updateGauge(Long.parseLong(projectId)));

        mongoTemplate.updateMulti(
                query,
                update,
                Graph.class
        );

    }

    // pie (준비, 진행, 완료)
    @Transactional
    public void updatePie(String projectId, String type) {
        // int[] datas = new int[]{10, 30, 50};
        int[] datas = scheduleService.updatePie(Long.parseLong(projectId));

        Graph graph = graphRepository.findAllByProjectIdAndType(projectId, type);

        for (int i = 0; i < datas.length; i++) {
            Map<String, Object> data = new HashMap<>();
            data.put("name", graph.getSeries().get(i).get("name"));
            data.put("data", datas[i]);
            graph.getSeries().set(i, data);
        }

        graphRepository.save(graph);

    }

    // table (구성원별 진행상태)
    public void updateTable(String projectId, String type) {

        // example data
        // projectId = 1, type = table
        Graph graph = graphRepository.findAllByProjectIdAndType(projectId, type);
        List<Map<String, Object>> series = graph.getSeries();

        // update 할 data를 담고 있는 Map
        // Map<String, Map<String, Integer>> updates = Map.of(
        //         "조예린", Map.of("준비", 55, "진행", 55, "완료", 55),
        //         "오목이", Map.of("준비", 3, "진행", 2, "완료", 1)
        // );

        Map<String, Map<String, Integer>> updates = scheduleService.updateTable(Long.parseLong(projectId));

        System.out.println("updates = " + updates);

        // 새로운 값으로 update
        for (Map<String, Object> data : series) {
            String memberName = (String) data.get("구성원명");
            if (updates.containsKey(memberName)) {
                Map<String, Integer> memberUpdates = updates.get(memberName);
                for (Map.Entry<String, Integer> entry : memberUpdates.entrySet()) {
                    data.put(entry.getKey(), entry.getValue());
                }
            }
        }

        graphRepository.save(graph);

    }

    // column
    public void updateColumn(String projectId, String type) {

        Graph graph = graphRepository.findAllByProjectIdAndType(projectId, type);
        List<String> categories = graph.getCategories();
        List<Map<String, Object>> series = graph.getSeries();

        // update할 categoreis (section명들)
        List<String> updateCategories = Arrays.asList("섹션명1", "섹션명2", "섹션명3");

        // update할 section별 진행상황
        Map<String, List<Integer>> updates = Map.of(
                "준비", List.of(1, 1, 1),
                "진행", List.of(2, 2, 2),
                "완료", List.of(3, 3, 3)
        );

        // categories를 업데이트
        graph.getCategories().clear(); // 기존 카테고리를 모두 지우고
        graph.getCategories().addAll(updateCategories); // 새로운 카테고리로 대체

        // 각 상태(준비, 진행, 완료)에 대한 값을 업데이트
        for (Map.Entry<String, List<Integer>> entry : updates.entrySet()) {
            String status = entry.getKey();
            List<Integer> values = entry.getValue();

            // 현재 상태에 대한 값을 업데이트
            for (Map<String, Object> seriesItem : series) {
                if (seriesItem.get("name").equals(status)) {
                    seriesItem.put("data", values);
                    break; // 해당 상태에 대한 값 업데이트 후 루프 종료
                }
            }
        }

        // 업데이트된 데이터를 저장
        graphRepository.save(graph);

    }

    // line
    public void updateLine(String projectId, String type) {

        Graph graph = graphRepository.findAllByProjectIdAndType(projectId, type);
        List<String> categories = graph.getCategories();
        List<Map<String, Object>> series = graph.getSeries();

        // List<LocalDate> dateCategories = new ArrayList<>();
        //
        // // update할 categories (날짜)
        // List<String> updateCategories = Arrays.asList(
        //     "01/01/2024",
        //     "02/02/2024",
        //     "03/03/2024",
        //     "04/04/2024",
        //     "05/05/2024",
        //     "06/06/2024",
        //     "07/07/2024",
        //     "08/08/2024",
        //     "09/09/2024",
        //     "10/10/2024"
        // );
        //
        String pattern = "dd/MM/yyyy";

        LocalDate startDate = LocalDate.parse(categories.get(0), DateTimeFormatter.ofPattern(pattern));
        LocalDate endDate = LocalDate.parse(categories.get(categories.size() - 1), DateTimeFormatter.ofPattern(pattern));

        List<LocalDate> dateCategories = projectService.divideWorkingDaysIntoTen(startDate, endDate);


        // string -> LocalDate

        for(String dateString : categories) {
            LocalDate date = LocalDate.parse(dateString, DateTimeFormatter.ofPattern(pattern));
            dateCategories.add(date);
        }

        int index = 0;

        // 현재 날짜가 각 날짜의 범위 내에 있는 지 확인하여 index 구하기
        LocalDate currentDate = LocalDate.now();

        for (int i = 0; i < dateCategories.size(); i++) {
            LocalDate start = i == 0 ? LocalDate.MIN : dateCategories.get(i - 1).plusDays(1);
            LocalDate end = dateCategories.get(i);

            if (currentDate.isAfter(start) && currentDate.isBefore(end)) {
                index = i;                          // 해당하는 범위의 인덱스 반환
            }
        }

        System.out.println("index = " + index);

        // update할 section별 진행상황
        int newdata = 3333; // test용 리터럴 값 (일정 스케쥴에서 전체진행률을 받아와야함)

        if (graph != null) {
            for (Map<String, Object> seriesEntry : series) {
                if ("실제진행률".equals(seriesEntry.get("name"))) {
                    List<Integer> data = (List<Integer>) seriesEntry.get("data");
                    if (index >= 0 && index < data.size()) {
                        data.set(index, newdata);
                        graphRepository.save(graph);
                    }
                    break;
                }
            }
        }

        // LocalDate -> String
        List<String> stringCategories = dateCategories.stream()
            .map(date -> date.format(DateTimeFormatter.ofPattern(pattern)))
                .collect(Collectors.toList());


        // categories update
        graph.getCategories().clear(); // 기존 카테고리를 모두 지우고
        graph.getCategories().addAll(stringCategories); // 새로운 카테고리로 대체

        graphRepository.save(graph);
    }
}
